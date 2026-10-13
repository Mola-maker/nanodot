# Verification run record

Keep records and captures in the existing ignored QA/SDD evidence area. Do not put keys, browser auth fragments, private conversations or private host content in committed fixtures. Use a harmless controlled host for shareable captures.

Use a JSON object (or an equivalent table) with these fields. This is a record contract, not an executable provider/tool schema:

- `feature`, `case`, `started_at`, `finished_at`, `owner`.
- `status`: `not_run`, `pass`, `fail`, `blocked`.
- `proof_level`: `source`, `artifact_integrity`, `unit`, `native_fixture`, `native_user_flow`, `real_provider`.
- `candidate`: receipt path/hash, source commit/fingerprint/dirty, main+test APK hashes, application IDs/certificate, ABI. Preserve source identity when later documentation-only commits differ.
- `device`: observed serial/AVD/API/ABI, locale, theme, font scale, rotation, animation settings; direct identity/hash check artifact and its time.
- `preconditions`, `entry_route`, `actions`, `expected`, `observed`. Describe what the user did and what changed, not just which function ran.
- `provider`: `not_used`, `fixture`, or `real`; capability tested and redacted outcome. Configured is not tested. A real-provider label requires actual request/result evidence from the app flow, not a setup check or mock reply.
- `artifacts`: original log/result/captures and their hashes. Before/action/after matters; `after.png` alone is not a behavioral verdict.
- `attempt`, `attempt_limit`, `hypothesis`, `cleanup`, `remaining`. Limits are set for the present check; they do not authorize more model calls or change Care's retry behavior.

Examples of honest outcomes: a fixture may be `pass/native_fixture` while the same feature is `not_run/real_provider`; a timed-out runner is `fail`, with `device_stop_required` recorded until the coordinator actually stops and verifies the known work. A missing entitlement may be `blocked` only for that proof level, not an excuse to skip independent checks.

`scripts/android/check_verification_evidence.py` checks local receipt bytes and fixture output. Its output deliberately does not bind a historical fixture to a current APK, verify an APK signature, inspect screenshots, query a device, or prove a provider call. The coordinator supplies direct observations and binds them in this run record. Never relabel that helper's `artifact_integrity` or `native_fixture` output as `native_user_flow` or `real_provider`.
