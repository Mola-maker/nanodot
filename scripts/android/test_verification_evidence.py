"""Integrity regressions for the offline verification evidence checker."""

from __future__ import annotations

import base64
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from check_verification_evidence import check_native, check_receipt


class EvidenceChecks(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        artifacts = []
        for kind in ("main", "test"):
            content = (kind + "-apk-bytes").encode()
            filename = f"{kind}.apk"
            (self.root / filename).write_bytes(content)
            artifacts.append(
                {
                    "kind": kind,
                    "applicationId": "io.github.nanodot.app" + (".test" if kind == "test" else ""),
                    "file": filename,
                    "sha256": hashlib.sha256(content).hexdigest(),
                    "certificateSha256": "a" * 64,
                    "abis": ["x86_64"] if kind == "main" else [],
                }
            )
        self.receipt = {
            "buildType": "debug",
            "abi": "x86_64",
            "sourceCommit": "b" * 40,
            "sourceFingerprint": "c" * 64,
            "sourceDirty": True,
            "diagnosticFlags": ["-Pnm.manualVoiceProof=false"],
            "artifacts": artifacts,
        }
        self.receipt_path = self.root / "receipt.json"
        self.installed = {
            "complete": True,
            "sourceCommit": "b" * 40,
            "sourceFingerprint": "c" * 64,
            "installed": [
                {"package": item["applicationId"], "sha256": item["sha256"]} for item in artifacts
            ],
        }
        self.installed_path = self.root / "installed.json"
        self.result = {
            "serial": "emulator-5586",
            "avd": "Nanodot_V2_QA",
            "case": "chat-layout",
            "tests": "io.github.nanomuse.dot.chat.DotForegroundLayoutTest#example",
            "expected": 1,
            "returncode": 0,
            "passed": True,
            "skipped_or_failed": False,
        }
        (self.root / "instrumentation.log").write_text("OK (1 test)\n")
        (self.root / "after.png").write_bytes(
            base64.b64decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ioAAAAASUVORK5CYII="
            )
        )

    def write_inputs(self) -> None:
        self.receipt_path.write_text(json.dumps(self.receipt))
        self.installed_path.write_text(json.dumps(self.installed))
        (self.root / "result.json").write_text(json.dumps(self.result))

    def test_pair_and_record_agree_without_claiming_live_state(self) -> None:
        self.write_inputs()
        report = check_receipt(self.receipt_path, self.installed_path)
        self.assertEqual("artifact_integrity", report["proof_level"])
        self.assertTrue(report["recorded_install_matches"])
        self.assertEqual(
            hashlib.sha256(b"main-apk-bytes").hexdigest(),
            report["apk_sha256"]["io.github.nanodot.app"],
        )

    def test_changed_apk_cannot_reuse_receipt(self) -> None:
        self.write_inputs()
        (self.root / "main.apk").write_bytes(b"other-build")
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            check_receipt(self.receipt_path)

    def test_other_installed_source_is_rejected(self) -> None:
        self.installed["sourceFingerprint"] = "d" * 64
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "another source"):
            check_receipt(self.receipt_path, self.installed_path)

    def test_partial_install_is_not_success(self) -> None:
        self.installed["complete"] = False
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            check_receipt(self.receipt_path, self.installed_path)

    def test_mixed_signers_are_rejected(self) -> None:
        self.receipt["artifacts"][1]["certificateSha256"] = "d" * 64
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "certificate metadata"):
            check_receipt(self.receipt_path)

    def test_apk_path_cannot_escape_candidate(self) -> None:
        self.receipt["artifacts"][0]["file"] = "../main.apk"
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "receipt sibling"):
            check_receipt(self.receipt_path)

    def test_diagnostic_candidate_is_not_ordinary_apk(self) -> None:
        self.receipt["diagnosticFlags"] = ["-Pnm.manualVoiceProof=true"]
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "disable manual voice"):
            check_receipt(self.receipt_path)

    def test_native_fixture_retains_specific_proof_level(self) -> None:
        self.write_inputs()
        report = check_native(self.root)
        self.assertEqual("native_fixture", report["proof_level"])
        self.assertEqual(1, report["passed_tests"])
        self.assertEqual(
            hashlib.sha256(b"OK (1 test)\n").hexdigest(),
            report["artifacts"]["instrumentation.log"],
        )

    def test_pass_boolean_does_not_override_failed_log(self) -> None:
        self.write_inputs()
        (self.root / "instrumentation.log").write_text(
            "INSTRUMENTATION_STATUS_CODE: -2\nOK (1 test)\n"
        )
        with self.assertRaisesRegex(ValueError, "failure or skip"):
            check_native(self.root)

    def test_wrong_test_count_is_not_accepted(self) -> None:
        self.write_inputs()
        (self.root / "instrumentation.log").write_text("OK (0 tests)\n")
        with self.assertRaisesRegex(ValueError, "count mismatch"):
            check_native(self.root)

    def test_timeout_requires_cleanup_even_if_pass_is_stale(self) -> None:
        self.result["device_stop_required"] = True
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "cleanup"):
            check_native(self.root)

    def test_other_device_or_missing_capture_is_rejected(self) -> None:
        self.result["serial"] = "emulator-5584"
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "serial"):
            check_native(self.root)
        self.result["serial"] = "emulator-5586"
        self.write_inputs()
        (self.root / "after.png").write_bytes(b"not-a-screenshot")
        with self.assertRaisesRegex(ValueError, "PNG"):
            check_native(self.root)


if __name__ == "__main__":
    unittest.main()
