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


def test_test_apk_allows_empty_versions_without_weakening_main():
    h = helper()
    value = "package: name='example.app.test' versionCode='' versionName=''\nsdkVersion:'26'\ntargetSdkVersion:'35'\n"
    assert h.parse_badging(value, test_apk=True)["versionName"] is None
    assert h.parse_badging(value, test_apk=True)["versionCode"] is None
    with pytest.raises(ValueError, match="versionCode"):
        h.parse_badging(value)


def test_scoped_checks_are_explicit_and_bounded():
    h = helper()
    assert h.gradle_options({}) == ["-Pnm.manualVoiceProof=false"]
    assert h.gradle_options({"NANODOT_TEST_SCOPE": "nanomuse"}) == [
        "-Pnm.manualVoiceProof=false",
        "--tests",
        "io.github.nanomuse.*",
    ]
    with pytest.raises(ValueError, match="NANODOT_TEST_SCOPE"):
        h.gradle_options({"NANODOT_TEST_SCOPE": "arbitrary"})


def test_offline_memory_options_are_literal_arguments():
    h = helper()
    assert h.gradle_options(
        {"NANODOT_OFFLINE": "1", "NANODOT_JVM_HEAP_MB": "4096", "NANODOT_KOTLIN_IN_PROCESS": "1"}
    ) == [
        "-Pnm.manualVoiceProof=false",
        "--offline",
        "-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8",
        "-Pkotlin.compiler.execution.strategy=in-process",
    ]
    with pytest.raises(ValueError, match="HEAP"):
        h.gradle_options({"NANODOT_JVM_HEAP_MB": "4096; echo unsafe"})


def test_compatibility_receipt_resolves_legacy_and_canonical_pair(tmp_path, monkeypatch):
    import json
    import sys

    h = helper()
    h.__file__ = str(tmp_path / "scripts/android/build_profile.py")
    (tmp_path / "config").mkdir()
    (tmp_path / "config/brand.nanodot.json").write_text(
        json.dumps({"androidApplicationId": "example.app"})
    )
    for subpath in ("debug/app-debug.apk", "androidTest/debug/app-debug-androidTest.apk"):
        p = tmp_path / "android/src/android/app/build/outputs/apk" / subpath
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(subpath.encode())
    identity = {"sourceCommit": "a" * 40}
    monkeypatch.setenv("NANODOT_SOURCE_IDENTITY", json.dumps(identity))
    monkeypatch.setattr(h, "source", lambda root: identity)
    monkeypatch.setattr(h, "doctor", lambda root: {"buildTools": "/fake/tools"})
    monkeypatch.setattr(
        sys, "argv", ["build_profile", "receipt", "--profile", "debug", "--with-test"]
    )

    def inspect(*args):
        if "verify" in args:
            return "Signer #1 certificate SHA-256 digest: abcdef"
        test = "androidTest" in args[-1]
        package = "example.app.test" if test else "example.app"
        versions = "versionCode='' versionName=''" if test else "versionCode='1' versionName='1.0'"
        return f"package: name='{package}' {versions}\nsdkVersion:'26'\ntargetSdkVersion:'35'\nnative-code: 'arm64-v8a'\n"

    monkeypatch.setattr(h, "run", inspect)
    h.main()
    output = tmp_path / "dist/nanodot"
    legacy = json.loads((output / "build-debug.json").read_text())
    assert (output / legacy["file"]).is_file()
    assert h.sha(output / legacy["file"]) == legacy["sha256"]
    canonical = output / legacy["candidateReceipt"]
    receipt = json.loads(canonical.read_text())
    assert receipt["sourceCommit"] == identity["sourceCommit"]
    assert len(receipt["artifacts"]) == 2
    for artifact in receipt["artifacts"]:
        assert h.sha(canonical.parent / artifact["file"]) == artifact["sha256"]
