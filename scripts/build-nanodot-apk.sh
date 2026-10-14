#!/usr/bin/env bash
# Build and inspect a local APK. Never publishes, repairs tools or installs devices.
set -euo pipefail
mode="${1:-debug}"
paired="${2:-}"
if [[ $# -gt 2 || ! "$mode" =~ ^(debug|release|doctor|check|debug-x86_64)$ || ( -n "$paired" && "$paired" != --with-test ) ]]; then
  echo "Usage: scripts/build-nanodot-apk.sh debug|release|doctor|check|debug-x86_64 [--with-test]" >&2
  exit 2
fi
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$mode" == release && ! -f "$root/android/keystore.properties" ]]; then
  echo "Release requires android/keystore.properties; no debug key fallback." >&2
  exit 1
fi
# Source optional local toolchain settings before the repository defaults/preflight.
[[ -z "${NANODOT_ENV_FILE:-}" ]] || source "$NANODOT_ENV_FILE"
[[ ! -f "$root/scripts/android/env.sh" ]] || source "$root/scripts/android/env.sh"
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "Set ANDROID_HOME to the required Android SDK." >&2
  exit 1
fi
python_cmd="${NANODOT_PYTHON:-python3}"
cd "$root"
helper=scripts/android/build_profile.py
if [[ "$mode" != check && "${NANODOT_TEST_SCOPE:-all}" != all ]]; then
  echo "NANODOT_TEST_SCOPE only applies to check" >&2; exit 2
fi
options_text="$("$python_cmd" "$helper" options)"
options=()
while IFS= read -r option; do
  [[ -z "$option" ]] || options+=("$option")
done <<< "$options_text"
GOTOOLCHAIN=local "$python_cmd" "$helper" doctor
[[ "$mode" != doctor ]] || exit 0
if [[ -n "$paired" && "$mode" != debug && "$mode" != debug-x86_64 ]]; then
  echo "--with-test requires debug or debug-x86_64" >&2; exit 2
fi
abi=arm64-v8a
[[ "$mode" != debug-x86_64 && "$mode" != check ]] || abi=x86_64
if [[ "$mode" != check ]]; then
  brand="$root/config/brand.nanodot.json"
  "$python_cmd" scripts/rebrand.py --brand-config "$brand"
  "$python_cmd" scripts/gen-android-icons.py --brand-config "$brand"
  [[ "$abi" != arm64-v8a ]] || bash scripts/android/build-natives.sh
fi
export NANODOT_SOURCE_IDENTITY
NANODOT_SOURCE_IDENTITY="$("$python_cmd" -c 'import sys,json;from pathlib import Path;sys.path.insert(0,"scripts/android");from build_profile import source;print(json.dumps(source(Path.cwd())))')"
tasks=(:app:assembleDebug)
[[ "$mode" != release ]] || tasks=(:app:assembleRelease)
[[ "$mode" != check ]] || tasks=(:app:testDebugUnitTest)
[[ -z "$paired" ]] || tasks+=(:app:assembleDebugAndroidTest)
# Remove prior APKs so a successful task cannot silently pair stale artifacts.
if [[ "$mode" != check ]]; then
  build_type=debug
  [[ "$mode" != release ]] || build_type=release
  rm -f "android/src/android/app/build/outputs/apk/$build_type/app-$build_type.apk"
  [[ -z "$paired" ]] || rm -f android/src/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi
(cd android/src/android && ./gradlew "${tasks[@]}" "${options[@]}" "-Pnm.abi=$abi" --console=plain --no-daemon \
  --max-workers="${NANODOT_GRADLE_WORKERS:-4}")
[[ "$mode" != check ]] || exit 0
args=(receipt --profile "$mode")
[[ -z "$paired" ]] || args+=(--with-test)
"$python_cmd" "$helper" "${args[@]}"
