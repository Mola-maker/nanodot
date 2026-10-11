#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
source /home/jlu/桌面/nanodot/work/android-dot-toolchain/env.sh
export GRADLE_USER_HOME="$repo_root/.superpowers/sdd/2026-10-10-android-dot-care/work/dot-ui-acceptance/gradle"
export ANDROID_USER_HOME="$repo_root/.superpowers/sdd/2026-10-10-android-dot-care/work/dot-ui-acceptance/android-user"
cd "$repo_root/android/src/android"
./gradlew :app:assembleDebugAndroidTest -I "$repo_root/work/dot-ui-acceptance/fixture-only.init.gradle" \
  --offline --console=plain --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx2048m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process
