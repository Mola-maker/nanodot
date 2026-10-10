#!/usr/bin/env bash
# The macOS packages of Nanodot Desktop, from the .app electron-builder left in harness/desktop/dist:
#
#   scripts/desktop-app/package-mac.sh <arch>          # arm64 | x64
#
# 1. a code signature over the whole bundle. Without a certificate in the environment it is
#    ad-hoc (`codesign -s -`): nothing is notarized, but a sealed ad-hoc bundle is what Gatekeeper
#    knows how to talk about ("Apple could not verify…", with *Open Anyway* in System Settings →
#    Privacy & Security). An unsigned bundle it calls "damaged" and offers to move to the Trash,
#    and on Apple silicon unsigned code does not run at all.
#    With the credentials below (the workflow passes the repository secrets of the same names;
#    docs/desktop.md says how they are made), the bundle is signed with the Developer ID
#    Application certificate under the hardened runtime, with the entitlements in
#    harness/desktop/resources/entitlements.mac.plist, notarized with notarytool and stapled — then
#    it opens like any other app:
#      MAC_CERT_P12_BASE64, MAC_CERT_PASSWORD     the certificate, exported from Keychain Access as .p12
#      APP_STORE_CONNECT_KEY_ID, APP_STORE_CONNECT_ISSUER_ID, APP_STORE_CONNECT_KEY_P8
#                                                an App Store Connect API key (the .p8's text) for notarytool
#      APPLE_TEAM_ID                              optional; checked against the certificate when set
#    The certificate alone signs without notarizing (Gatekeeper then still asks once).
# 2. Nanodot-Desktop-<version>-mac-<arch>.zip — `ditto`, the way Finder makes archives, which
#    keeps symlinks, resource forks and permissions; unzip, drag to Applications.
# 3. Nanodot-Desktop-<version>-mac-<arch>.dmg — an APFS image made with `hdiutil` directly.
#    electron-builder's HFS+ image copied with Finder error -36 on some Macs (0.1.20, 0.1.21).
#
# APP_DIR, APP_NAME and ARTIFACT name another app directory, bundle name and file prefix.
# The app's inner code is the staged dsh under Resources/dsh (Node addons and libraries)
# besides the runtime.
set -euo pipefail
arch="${1:?arch: arm64 | x64}"
case "$arch" in
  arm64) mac_dir=mac-arm64; native_arch=arm64 ;;
  x64) mac_dir=mac; native_arch=x86_64 ;;
  *) echo "unsupported macOS architecture: $arch" >&2; exit 1 ;;
esac
here="$(cd "$(dirname "$0")/../.." && pwd)"
app_dir="${APP_DIR:-$here/harness/desktop}"
case "$app_dir" in /*) ;; *) app_dir="$here/$app_dir" ;; esac
app_name="${APP_NAME:-Nanodot}"
artifact="${ARTIFACT:-Nanodot-Desktop}"
version="$(node -p "require('$app_dir/package.json').version")"
# The runtime and staged native modules belong to this architecture only.
bundle_dir="$app_dir/dist/$mac_dir"
app="$bundle_dir/$app_name.app"
if [ ! -d "$app" ]; then
  # electron-builder names the bundle after `executableName` when the config sets one
  # (Nanodot Desktop builds as nanodot-desktop.app); what people drag into Applications
  # should carry the product's name, so the bundle is renamed before it is signed
  shopt -s nullglob
  candidates=("$bundle_dir"/*.app)
  [ "${#candidates[@]}" = 1 ] || { echo "expected one .app under $bundle_dir" >&2; exit 1; }
  mv "${candidates[0]}" "$app"
  echo "renamed $(basename "${candidates[0]}") -> $app_name.app"
fi
executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app/Contents/Info.plist")"
lipo "$app/Contents/MacOS/$executable" -verify_arch "$native_arch"
lipo "$app/Contents/Resources/runtime/nanomuse" -verify_arch "$native_arch"
out="$app_dir/dist"
name="$artifact-$version-mac-$arch"

# ---------------------------------------------------------------------------- the identity
identity="-"
sign_flags=(--timestamp=none)
work="$(mktemp -d)"
keychain=""
cleanup() {
  if [ -n "$keychain" ]; then security delete-keychain "$keychain" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT

if [ -n "${MAC_CERT_P12_BASE64:-}" ]; then
  echo "== Developer ID certificate"
  # a keychain of its own, so nothing of the machine's is touched and nothing stays behind
  keychain="$work/build.keychain-db"
  kpass="$(uuidgen)"
  security create-keychain -p "$kpass" "$keychain"
  security set-keychain-settings -lut 21600 "$keychain"
  security unlock-keychain -p "$kpass" "$keychain"
  printf '%s' "$MAC_CERT_P12_BASE64" | base64 --decode > "$work/cert.p12"
  security import "$work/cert.p12" -k "$keychain" -P "${MAC_CERT_PASSWORD:-}" -T /usr/bin/codesign -T /usr/bin/security >/dev/null
  rm -f "$work/cert.p12"
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$kpass" "$keychain" >/dev/null
  # search this keychain too, in front of the user's
  # shellcheck disable=SC2046
  security list-keychains -d user -s "$keychain" $(security list-keychains -d user | tr -d '" ')
  identity="$(security find-identity -v -p codesigning "$keychain" | awk -F'"' '/Developer ID Application/ {print $2; exit}')"
  [ -n "$identity" ] || { echo "the certificate carries no 'Developer ID Application' identity" >&2; exit 1; }
  if [ -n "${APPLE_TEAM_ID:-}" ] && [[ "$identity" != *"($APPLE_TEAM_ID)"* ]]; then
    echo "the certificate's team is not APPLE_TEAM_ID ($identity)" >&2; exit 1
  fi
  echo "signing as: $identity"
  sign_flags=(--timestamp --options runtime --entitlements "$app_dir/resources/entitlements.mac.plist")
fi

# ---------------------------------------------------------------------------- the signature
echo "== signature"
# inner code first: the bundled runtime's executables and libraries, the staged harness's
# Node addons and the unpacked native modules (nut.js, the macOS permission helpers) are
# loose files under Resources, which --deep does not visit — unsigned, notarization refuses them
for inner in runtime dsh app.asar.unpacked; do
  [ -d "$app/Contents/Resources/$inner" ] || continue
  while IFS= read -r -d '' binary; do
    # Shell/Python scripts are resources; every Mach-O signature must succeed.
    if file -b "$binary" | grep -q '^Mach-O'; then
      codesign --force --sign "$identity" "${sign_flags[@]}" "$binary"
    fi
  done < <(find "$app/Contents/Resources/$inner" -type f \
    \( -perm -u+x -o -name "*.so" -o -name "*.dylib" -o -name "*.node" \) -print0)
done
# "Nanodot Computer Use.app" (harness/desktop/mac/computer-use): the helper that holds Screen
# Recording and Accessibility. Signed as a bundle of its own, before the outer app, with the
# same identity and the hardened runtime but none of the app's entitlements (it is a plain
# Foundation program: no JIT, no foreign libraries). Its identifier is what the privacy panes
# remember: with a Developer ID the designated requirement is the same from build to build and
# the grant survives updates; ad-hoc, it is keyed to this binary's hash and each build starts
# over (docs/desktop.md "macOS permissions").
helper="$app/Contents/Helpers/Nanodot Computer Use.app"
if [ -d "$helper" ]; then
  lipo "$helper/Contents/MacOS/Nanodot Computer Use" -verify_arch "$native_arch"
  helper_flags=(--timestamp=none)
  [ "$identity" != "-" ] && helper_flags=(--timestamp --options runtime)
  codesign --force --sign "$identity" "${helper_flags[@]}" --identifier io.github.nanodot.desktop.computer-use "$helper"
  codesign --verify --strict "$helper"
  echo "helper signature ok"
fi
codesign --force --deep --sign "$identity" "${sign_flags[@]}" "$app"
codesign --verify --deep --strict "$app"
echo "signature ok"

# ---------------------------------------------------------------------------- notarization
notarize() { # file — submits it, waits, and fails the build when Apple refuses
  xcrun notarytool submit "$1" --key "$work/AuthKey.p8" --key-id "$APP_STORE_CONNECT_KEY_ID" \
    --issuer "$APP_STORE_CONNECT_ISSUER_ID" --wait --timeout 45m
}
notarized=0
if [ "$identity" != "-" ] && [ -n "${APP_STORE_CONNECT_KEY_P8:-}" ]; then
  echo "== notarization (the app)"
  printf '%s\n' "$APP_STORE_CONNECT_KEY_P8" > "$work/AuthKey.p8"
  ditto -c -k --sequesterRsrc --keepParent "$app" "$work/for-notary.zip"
  notarize "$work/for-notary.zip"
  xcrun stapler staple "$app"
  echo "stapled"
  notarized=1
elif [ "$identity" = "-" ]; then
  echo "ad-hoc signed; no Developer ID certificate or notarization"
elif [ "$identity" != "-" ]; then
  echo "signed, not notarized: no App Store Connect key in the environment"
fi

echo "== zip"
rm -f "$out/$name.zip"
ditto -c -k --sequesterRsrc --keepParent "$app" "$out/$name.zip"

echo "== dmg"
stage="$(mktemp -d)"
cp -R "$app" "$stage/"
ln -s /Applications "$stage/Applications"
rm -f "$out/$name.dmg"
hdiutil create -volname "$app_name $version" -srcfolder "$stage" -ov -fs APFS -format UDZO -quiet "$out/$name.dmg"
rm -rf "$stage"
hdiutil verify -quiet "$out/$name.dmg"
echo "dmg ok"
if [ "$identity" != "-" ]; then
  codesign --force --sign "$identity" --timestamp "$out/$name.dmg"
  if [ "$notarized" = 1 ]; then
    echo "== notarization (the dmg)"
    notarize "$out/$name.dmg"
    xcrun stapler staple "$out/$name.dmg"
    echo "dmg stapled"
  fi
fi

# electron-builder's own mac artifacts, if any, must not go on the release next to these
find "$out" -maxdepth 1 -type f \( -name "*.dmg" -o -name "*.zip" -o -name "*.blockmap" \) ! -name "$name.*" -delete
ls -la "$out"/*.zip "$out"/*.dmg
