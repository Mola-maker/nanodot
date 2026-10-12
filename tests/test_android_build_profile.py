"""Reject invalid toolchain/artifact claims before publishing local candidates."""

import importlib.util
from pathlib import Path

import pytest

PATH = Path(__file__).resolve().parents[1] / "scripts/android/build_profile.py"


def helper():
    assert PATH.exists(), "profile validation helper is missing"
    spec = importlib.util.spec_from_file_location("build_profile", PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_x86_profile_never_requests_arm64_natives():
    assert helper().profile("debug-x86_64", True) == (
        "debug",
        "x86_64",
        [":app:assembleDebug", ":app:assembleDebugAndroidTest"],
        False,
    )


def test_check_only_runs_unit_tests():
    assert helper().profile("check", False)[2] == [":app:testDebugUnitTest"]


def test_badging_records_actual_sdk_and_rejects_missing_target():
    h = helper()
    value = "package: name='example.app' versionCode='7' versionName='1.2'\nsdkVersion:'26'\ntargetSdkVersion:'35'\nnative-code: 'x86_64'\n"
    assert h.parse_badging(value)["targetSdk"] == 35
    with pytest.raises(ValueError, match="targetSdkVersion"):
        h.parse_badging(value.replace("targetSdkVersion:'35'", ""))


def test_misversioned_tool_fails():
    with pytest.raises(ValueError, match="JDK 21"):
        helper().validate_java('openjdk version "17.0.10"')


def test_artifact_mismatched_abi_fails():
    with pytest.raises(ValueError, match="ABI"):
        helper().validate_artifact(
            {"applicationId": "example.app", "abis": ["arm64-v8a"]}, "example.app", "x86_64"
        )


def test_source_identity_detects_uncommitted_byte_change(tmp_path):
    import subprocess

    subprocess.run(["git", "init", str(tmp_path)], check=True, capture_output=True)
    path = tmp_path / "source.txt"
    path.write_text("first")
    subprocess.run(["git", "add", "."], cwd=tmp_path, check=True)
    subprocess.run(
        [
            "git",
            "-c",
            "user.name=Test",
            "-c",
            "user.email=test@example.invalid",
            "commit",
            "-m",
            "test",
        ],
        cwd=tmp_path,
        check=True,
        capture_output=True,
    )
    before = helper().source(tmp_path)
    path.write_text("second")
    after = helper().source(tmp_path)
    assert before["sourceCommit"] == after["sourceCommit"]
    assert before["sourceFingerprint"] != after["sourceFingerprint"]
    assert after["sourceDirty"]
