#!/usr/bin/env bash
# Build "Nanodot Computer Use.app" — the helper that holds the hands' macOS permissions
# (Sources/main.swift says what it does; docs/desktop.md "macOS permissions" says why).
#
#   harness/desktop/mac/computer-use/build.sh            → build/Nanodot Computer Use.app
#
# Plain swiftc, no Xcode project: one binary per architecture (arm64 and x86_64, or ARCHS
# to pick), joined with lipo; the frameworks (AppKit, Network, CoreGraphics, ScreenCaptureKit)
# come in through the imports; Info.plist from the template with the desktop's version; the
# app icon from resources/app-icon.png when iconutil is there (the panes show it); an ad-hoc
# signature so the bundle runs at all — scripts/desktop-app/package-mac.sh signs it again
# with the release identity before the outer app. electron-builder copies the result into
# Nanodot.app/Contents/Helpers (electron-builder.yml, mac.extraFiles).
#
#   VERSION                     the bundle's version (default: harness/desktop/package.json)
#   ARCHS                       "arm64 x86_64" (default) — one of them for a quicker local build
#   MACOSX_DEPLOYMENT_TARGET    12.3 by default — the first macOS with ScreenCaptureKit, which the
#                               binary links; the SCK capture itself runs on 14+ (availability-guarded,
#                               Sources/ScreenCapture.swift), CoreGraphics before that, and the
#                               deprecations of CGDisplayCreateImage (15) / CGWindowListCreateImage (14)
#                               stay quiet below 14
#   OUT_DIR                     where build/ goes
#
# Warnings: the compiler's output is kept and any `warning:` line is surfaced as a GitHub
# annotation (::warning::) so it is seen without failing the release build; a NanoMuse Swift
# file with a warning is treated as a failure by the project's rules — fix it.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
desktop="$(cd "$here/../.." && pwd)"
out="${OUT_DIR:-$here/build}"
name="Nanodot Computer Use"
app="$out/$name.app"
min="${MACOSX_DEPLOYMENT_TARGET:-12.3}"
archs="${ARCHS:-arm64 x86_64}"
version="${VERSION:-$(node -p "require('$desktop/package.json').version" 2>/dev/null || echo 0.0.0)}"

command -v swiftc >/dev/null || { echo "swiftc is not here — this builds on macOS with the Xcode command line tools" >&2; exit 1; }
sdk="$(xcrun --show-sdk-path --sdk macosx)"

rm -rf "$app" "$out/obj"
mkdir -p "$out/obj" "$app/Contents/MacOS" "$app/Contents/Resources"

echo "== swiftc ($archs, macOS $min+)"
binaries=()
for arch in $archs; do
  log="$out/obj/swiftc-$arch.log"
  if ! swiftc -O -swift-version 5 -target "$arch-apple-macos$min" -sdk "$sdk" \
      -module-name NanoMuseComputerUse "$here"/Sources/*.swift -o "$out/obj/$arch" 2> "$log"; then
    cat "$log" >&2
    echo "swiftc failed for $arch" >&2
    exit 1
  fi
  if grep -q "warning:" "$log"; then
    cat "$log" >&2
    echo "::warning file=harness/desktop/mac/computer-use/build.sh::Swift warnings in the $arch build of $name (see the step's log)"
  fi
  binaries+=("$out/obj/$arch")
done
if [ "${#binaries[@]}" -gt 1 ]; then
  lipo -create "${binaries[@]}" -output "$app/Contents/MacOS/$name"
else
  cp "${binaries[0]}" "$app/Contents/MacOS/$name"
fi
chmod 755 "$app/Contents/MacOS/$name"

echo "== bundle"
sed -e "s/__VERSION__/$version/g" -e "s/__MIN__/$min/g" "$here/Info.plist" > "$app/Contents/Info.plist"
printf 'APPL????' > "$app/Contents/PkgInfo"
icon="$desktop/resources/app-icon.png"
if command -v iconutil >/dev/null && command -v sips >/dev/null && [ -f "$icon" ]; then
  iconset="$out/obj/AppIcon.iconset"
  mkdir -p "$iconset"
  for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$icon" --out "$iconset/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z "$double" "$double" "$icon" --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
  done
  iconutil -c icns "$iconset" -o "$app/Contents/Resources/AppIcon.icns"
fi

echo "== ad-hoc signature"
codesign --force --sign - --identifier io.github.nanodot.desktop.computer-use "$app"
codesign --verify --strict "$app" && echo "signature ok"
"$app/Contents/MacOS/$name" --version
lipo -info "$app/Contents/MacOS/$name" 2>/dev/null || true
echo "built $app"
