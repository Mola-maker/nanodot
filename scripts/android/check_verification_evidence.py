#!/usr/bin/env python3
"""Check local Android evidence integrity without driving a device or calling a provider."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), "Expected a JSON object")
    return value


def sha_value(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[a-fA-F0-9]{64}", value) is not None


def check_receipt(path: Path, installed: Path | None = None) -> dict:
    receipt = read_json(path)
    require(receipt.get("buildType") == "debug", "Expected a paired debug candidate")
    require(receipt.get("abi") in ("x86_64", "arm64-v8a"), "Unsupported candidate ABI")
    require(
        "-Pnm.manualVoiceProof=false" in receipt.get("diagnosticFlags", []),
        "Ordinary candidate must disable manual voice diagnostics",
    )
    require(
        isinstance(receipt.get("sourceCommit"), str)
        and re.fullmatch(r"[a-f0-9]{40}", receipt["sourceCommit"]) is not None
        and sha_value(receipt.get("sourceFingerprint"))
        and isinstance(receipt.get("sourceDirty"), bool),
        "Missing source identity",
    )
    artifacts = receipt.get("artifacts")
    require(isinstance(artifacts, list) and len(artifacts) == 2, "Expected main/test APK pair")
    require(all(isinstance(item, dict) for item in artifacts), "Invalid artifact entry")
    require({item.get("kind") for item in artifacts} == {"main", "test"}, "Invalid pair kinds")
    packages = {}
    certificates = set()
    for artifact in artifacts:
        package = "io.github.nanodot.app" + (".test" if artifact["kind"] == "test" else "")
        require(artifact.get("applicationId") == package, "Unexpected APK package")
        filename = artifact.get("file")
        require(
            isinstance(filename, str) and filename == Path(filename).name,
            "APK must be a receipt sibling",
        )
        apk = path.parent / filename
        require(apk.resolve().parent == path.parent.resolve(), "APK escaped receipt directory")
        require(sha_value(artifact.get("sha256")), "Missing APK digest")
        require(digest(apk) == artifact["sha256"].lower(), "APK bytes differ from receipt")
        require(sha_value(artifact.get("certificateSha256")), "Missing certificate digest")
        certificates.add(artifact["certificateSha256"].lower())
        if artifact["kind"] == "main":
            require(artifact.get("abis") == [receipt["abi"]], "Main APK ABI metadata mismatch")
        packages[package] = artifact["sha256"].lower()
    require(len(certificates) == 1, "Main/test certificate metadata differs")
    if installed is not None:
        record = read_json(installed)
        require(record.get("complete") is True, "Incomplete recorded installation")
        require(
            record.get("sourceCommit") == receipt["sourceCommit"]
            and record.get("sourceFingerprint") == receipt["sourceFingerprint"],
            "Installation record refers to another source candidate",
        )
        rows = record.get("installed")
        require(isinstance(rows, list) and len(rows) == 2, "Installation record lacks APK pair")
        require(all(isinstance(row, dict) for row in rows), "Invalid installed entry")
        require(
            {row.get("package"): row.get("sha256") for row in rows} == packages,
            "Recorded installed APK digests differ",
        )
    return {
        "status": "pass",
        "proof_level": "artifact_integrity",
        "source_commit": receipt["sourceCommit"],
        "source_fingerprint": receipt["sourceFingerprint"],
        "source_dirty": receipt["sourceDirty"],
        "abi": receipt["abi"],
        "apk_sha256": packages,
        "recorded_install_matches": installed is not None,
        "limits": "No live device query, APK signature verification, or native-flow proof performed.",
    }


def check_native(directory: Path) -> dict:
    result = read_json(directory / "result.json")
    expected = result.get("expected")
    require(type(expected) is int and expected > 0, "Invalid expected fixture count")
    require(result.get("serial") == "emulator-5586", "Unexpected recorded serial")
    require(result.get("avd") == "Nanodot_V2_QA", "Unexpected recorded AVD")
    require(result.get("passed") is True and result.get("returncode") == 0, "Fixture failed")
    require(
        not any(
            result.get(key) for key in ("timed_out", "device_stop_required", "skipped_or_failed")
        ),
        "Fixture timed out, skipped, or needs device cleanup",
    )
    log_path = directory / "instrumentation.log"
    output = log_path.read_text(encoding="utf-8")
    require(re.findall(r"OK \((\d+) tests?\)", output) == [str(expected)], "JUnit count mismatch")
    require(
        not re.search(r"INSTRUMENTATION_STATUS_CODE: -[234]\b", output)
        and not any(
            word in output for word in ("FAILURES", "INSTRUMENTATION_FAILED", "Process crashed")
        ),
        "Instrumentation log contains failure or skip",
    )
    screenshot = directory / "after.png"
    with screenshot.open("rb") as stream:
        require(stream.read(8) == b"\x89PNG\r\n\x1a\n", "Missing PNG capture")
    return {
        "status": "pass",
        "proof_level": "native_fixture",
        "case": result.get("case"),
        "tests": result.get("tests"),
        "passed_tests": expected,
        "artifacts": {
            name: digest(directory / name)
            for name in ("result.json", "instrumentation.log", "after.png")
        },
        "limits": "Fixture output only; no APK/time binding, visual verdict, or real-provider proof.",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    receipt = commands.add_parser("receipt")
    receipt.add_argument("path", type=Path)
    receipt.add_argument("--installed", type=Path)
    native = commands.add_parser("native")
    native.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        result = (
            check_receipt(args.path, args.installed)
            if args.command == "receipt"
            else check_native(args.directory)
        )
    except (OSError, ValueError, TypeError, KeyError) as error:
        raise SystemExit(f"Evidence check failed: {error}") from None
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
