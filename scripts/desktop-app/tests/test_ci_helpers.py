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
