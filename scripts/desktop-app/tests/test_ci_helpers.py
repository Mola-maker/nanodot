"""Portable regressions for CI's runtime timeout and downloadable package evidence."""

from __future__ import annotations

import hashlib
import importlib.util
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name: str):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), SCRIPTS / f"{name}.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RuntimeCheckTests(unittest.TestCase):
    @unittest.skipUnless(os.name == "posix", "PyInstaller framework links are POSIX-only")
    def test_runtime_staging_preserves_framework_links_and_library_aliases(self):
        runtime = load("build-runtime")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dist = root / "dist"
            target = root / "runtime"

            def freeze(*_cmd, **_kwargs):
                built = dist / "nanomuse"
                version = built / "_internal/Python.framework/Versions/3.12"
                (version / "Resources").mkdir(parents=True)
                (built / "nanomuse").write_bytes(b"runtime executable")
                (version / "Python").write_bytes(b"framework library")
                (version / "Resources/Info.plist").write_bytes(b"framework metadata")
                framework = version.parent.parent
                (framework / "Versions/Current").symlink_to("3.12", target_is_directory=True)
                (framework / "Python").symlink_to("Versions/Current/Python")
                (framework / "Resources").symlink_to(
                    "Versions/Current/Resources", target_is_directory=True
                )
                (built / "_internal/Python").symlink_to("Python.framework/Python")

            with patch.object(runtime, "DIST", dist), patch.object(runtime, "run", freeze):
                executable = runtime.build(target)
            self.assertEqual(executable.read_bytes(), b"runtime executable")
            for name in (
                "Python",
                "Python.framework/Python",
                "Python.framework/Resources",
                "Python.framework/Versions/Current",
            ):
                self.assertTrue((target / "_internal" / name).is_symlink(), name)
            self.assertEqual((target / "_internal/Python").read_bytes(), b"framework library")
            self.assertEqual(
                (target / "_internal/Python.framework/Resources/Info.plist").read_bytes(),
                b"framework metadata",
            )

    def test_timeout_stops_a_live_verbose_runtime_without_waiting_for_pipe_eof(self):
        runtime = load("build-runtime")
        popen = subprocess.Popen
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            child = root / "child.py"
            ready = root / "ready"
            child.write_text(
                "import pathlib, time\n"
                "print('startup output' * 20000, flush=True)\n"
                f"pathlib.Path({str(ready)!r}).touch()\n"
                "time.sleep(300)\n",
                encoding="utf-8",
            )
            children = []

            def spawn(_argv, **kwargs):
                process = popen([sys.executable, str(child)], **kwargs)
                children.append(process)
                deadline = time.monotonic() + 5
                while not ready.exists() and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(ready.exists(), "verbose startup blocked on its output")
                return process

            with (
                patch.object(runtime, "ROOT", root),
                patch.object(runtime.socket, "socket") as socket_factory,
                patch.object(runtime.subprocess, "Popen", side_effect=spawn),
                patch.object(runtime.time, "time", side_effect=[0, 61]),
            ):
                socket_factory.return_value.__enter__.return_value.getsockname.return_value = (
                    "127.0.0.1",
                    54321,
                )
                start = time.monotonic()
                with self.assertRaisesRegex(SystemExit, "startup output"):
                    runtime.check(root / "fake-runtime")
                self.assertLess(time.monotonic() - start, 10)
            self.assertIsNotNone(children[0].poll())
            self.assertFalse((root / "build/runtime-check-home").exists())
            self.assertFalse((root / "build/runtime-check-cwd").exists())


class ManifestTests(unittest.TestCase):
    def test_checksums_hash_installer_bytes_and_exclude_unrelated_files(self):
        manifest = load("artifact-manifest")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            name = "Nanodot-Desktop-1.0.1-win-x64.exe"
            (root / name).write_bytes(b"installer bytes")
            (root / "old-upstream.exe").write_bytes(b"not a Nanodot installer")
            with patch.dict(os.environ, {}, clear=True):
                manifest.write_manifest(root, "windows-x64", "abc123")
            expected = hashlib.sha256(b"installer bytes").hexdigest()
            self.assertEqual(
                (root / "SHA256SUMS-windows-x64.txt").read_text(), f"{expected}  {name}\n"
            )
            self.assertIn("SmartScreen", (root / "BUILD-INFO-windows-x64.txt").read_text())

    def test_missing_mac_installer_fails_and_ad_hoc_status_is_explicit(self):
        manifest = load("artifact-manifest")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Nanodot-Desktop-1.0.1-mac-arm64.zip").write_bytes(b"zip")
            with self.assertRaisesRegex(SystemExit, "missing macos-arm64 installer"):
                manifest.write_manifest(root, "macos-arm64", "abc123")
            (root / "Nanodot-Desktop-1.0.1-mac-arm64.dmg").write_bytes(b"dmg")
            with patch.dict(os.environ, {}, clear=True):
                manifest.write_manifest(root, "macos-arm64", "abc123")
            self.assertIn(
                "Ad-hoc signed; not notarized", (root / "BUILD-INFO-macos-arm64.txt").read_text()
            )


if __name__ == "__main__":
    unittest.main()
