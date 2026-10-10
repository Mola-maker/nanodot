"""The build entry point refuses publishing and unsigned release builds."""

import os
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run_wrapper(tmp_path: Path, *args: str) -> subprocess.CompletedProcess[str]:
    scripts = tmp_path / "scripts"
    scripts.mkdir()
    shutil.copy2(ROOT / "scripts/build-nanodot-apk.sh", scripts)
    return subprocess.run(
        ["bash", str(scripts / "build-nanodot-apk.sh"), *args],
        capture_output=True,
        text=True,
        env={**os.environ, "ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""},
        timeout=10,
    )


def test_release_requires_explicit_signing_before_toolchain(tmp_path: Path) -> None:
    result = run_wrapper(tmp_path, "release")
    assert result.returncode != 0
    assert "keystore.properties" in result.stderr
    assert "debug key" in result.stderr


def test_publish_is_not_a_build_mode(tmp_path: Path) -> None:
    result = run_wrapper(tmp_path, "--publish")
    assert result.returncode != 0
    assert "debug|release" in result.stderr


def test_debug_reports_missing_sdk(tmp_path: Path) -> None:
    result = run_wrapper(tmp_path, "debug")
    assert result.returncode != 0
    assert "ANDROID_HOME" in result.stderr
