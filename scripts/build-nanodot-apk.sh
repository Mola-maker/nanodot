#!/usr/bin/env bash
# Build and inspect a local APK. This script never publishes or tags a release.
set -euo pipefail

mode="${1:-debug}"
if [[ $# -gt 1 || ( "$mode" != debug && "$mode" != release ) ]]; then
  echo "Usage: scripts/build-nanodot-apk.sh debug|release" >&2
  exit 2
fi
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$mode" == release && ! -f "$root/android/keystore.properties" ]]; then
  echo "Release requires android/keystore.properties; no debug key fallback." >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "Set ANDROID_HOME to an Android SDK with API 36, build tools, NDK r27c and CMake 3.22.1." >&2
  exit 1
fi
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
python_cmd="${NANODOT_PYTHON:-python3}"
brand="$root/config/brand.nanodot.json"
cd "$root"
"$python_cmd" scripts/rebrand.py --brand-config "$brand"
"$python_cmd" scripts/gen-android-icons.py --brand-config "$brand"
bash scripts/android/build-natives.sh

gradle_task=assembleDebug
[[ "$mode" == release ]] && gradle_task=assembleRelease
(cd android/src/android && ./gradlew ":app:$gradle_task" --console=plain --no-daemon \
  --max-workers="${NANODOT_GRADLE_WORKERS:-4}")

apk="android/src/android/app/build/outputs/apk/$mode/app-$mode.apk"
[[ -f "$apk" ]] || { echo "Gradle did not produce $apk" >&2; exit 1; }
bt="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
[[ -x "$bt/aapt" && -x "$bt/apksigner" ]] || { echo "Android build tools are missing." >&2; exit 1; }
mkdir -p dist/nanodot
badging="$("$bt/aapt" dump badging "$apk")"
"$bt/apksigner" verify --print-certs "$apk" > dist/nanodot/signature-"$mode".txt
printf '%s\n' "$badging" > dist/nanodot/badging-"$mode".txt
"$python_cmd" - "$root" "$apk" "$mode" <<'PY'
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

root, apk, mode = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
brand = json.loads((root / "config/brand.nanodot.json").read_text())
output = root / "dist/nanodot"
badging = (output / f"badging-{mode}.txt").read_text()
package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
assert package and package[1] == brand["androidApplicationId"], "APK applicationId mismatch"
assert f"application-label:'{brand['displayName']}'" in badging, "APK label mismatch"
assert "sdkVersion:'26'" in badging, "APK minSdk mismatch"
assert "native-code: 'arm64-v8a'" in badging, "APK ABI mismatch"
target = output / f"Nanodot-{package[3]}-{mode}-arm64-v8a.apk"
shutil.copy2(apk, target)
digest = hashlib.sha256(target.read_bytes()).hexdigest()
(output / f"{target.name}.sha256").write_text(f"{digest}  {target.name}\n")
metadata = {
    "file": target.name, "sha256": digest, "buildType": mode,
    "applicationId": package[1], "versionCode": int(package[2]), "versionName": package[3],
    "minSdk": 26, "targetSdk": 35, "abi": "arm64-v8a",
    "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
    "sourceDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root)),
    "signature": (output / f"signature-{mode}.txt").read_text(),
}
(output / f"build-{mode}.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
print(f"Built {target.name}\nSHA256 {digest}")
PY
