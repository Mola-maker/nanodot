# Build Nanodot P0

Nanodot source is [Mola-maker/nanodot](https://github.com/Mola-maker/nanodot), based on
nanoMuse baseline `f627f33ccc8ab70b9b59322929e5f15a2b1bec64` and OpenMinis 1.13.
P0 keeps version **1.0.1** and Android versionCode **44**. Version changes belong to a
separate release change. The [brand contract](brand.md) defines the shared identity.

## Toolchain

Use this P0 toolchain; do not upgrade Kotlin, AGP or Compose as part of branding.

| Component | P0 value |
|---|---|
| JDK | 21 |
| Android minSdk / targetSdk / compileSdk | 26 / 35 / 36 |
| Android NDK | `27.2.12479018` (r27c) |
| SDK CMake | `3.22.1` |
| Go | 1.26 |
| Android native ABI | `arm64-v8a` |
| Asset generation | Python 3 with Pillow |

Set `JAVA_HOME`, `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), `ANDROID_NDK_HOME`, `GOROOT`
and `GOPATH` to your local installations. Put the JDK, Go, gomobile and gobind on `PATH`.
`scripts/android/env.sh` can help select installed tools; its fallback paths are not a
guarantee that those versions are installed. Keep local paths and signing material out of
source control.

## Prepare the checkout

From the repository root:

```sh
git submodule update --init android/deps/proot
python3 -m venv .venv
.venv/bin/python -m pip install -e '.[dev]'
.venv/bin/python scripts/rebrand.py --brand-config config/brand.nanodot.json
.venv/bin/python scripts/gen-nanodot-assets.py --brand-config config/brand.nanodot.json
.venv/bin/python scripts/gen-android-icons.py --brand-config config/brand.nanodot.json
.venv/bin/python scripts/gen-ios-icons.py --brand-config config/brand.nanodot.json
.venv/bin/python scripts/rebrand.py --brand-config config/brand.nanodot.json
```

The second rebrand invocation should print `clean`. Review the generated diff before
building. `scripts/android/build-natives.sh` prepares proot, its loader, the Alpine rootfs
and `rclone.aar`; check the complete outputs, not only that the script started. Android
does not require an iOS iSH build environment.

## Debug APK

```sh
NANODOT_PYTHON="$PWD/.venv/bin/python" bash scripts/build-nanodot-apk.sh debug
```

The wrapper regenerates Android branding/icons, builds native dependencies and runs
`:app:assembleDebug`. It checks package identity, label, minSdk, arm64 ABI and signature,
then writes the APK, SHA256, badging, signature report and JSON build metadata under
`dist/nanodot/`. Debug artifacts carry `debug` in their filename and use debug signing.
The wrapper does not upload, tag or publish.

## Release signing

Use a dedicated, long-lived Nanodot keystore and keep it for later updates. Configure the
git-ignored `android/keystore.properties` locally with `storeFile`, `storePassword`,
`keyAlias` and `keyPassword`. An absolute `storeFile` path avoids ambiguity; relative paths
are resolved by the app Gradle project. Never commit a key or password.

```sh
NANODOT_PYTHON="$PWD/.venv/bin/python" bash scripts/build-nanodot-apk.sh release
```

Missing or incomplete signing configuration fails the release build. Gradle also checks
signing when invoked directly. Release never falls back to the debug keystore. Verify
the reported signing certificate against the intended key; record the exact tool versions,
source revision, dirty-tree status and build logs alongside the generated metadata.

## Identity, data and updates

Android applicationId is `io.github.nanodot.app`; the external scheme is `nanodot://`.
The namespace `com.openminis.app`, internal `io.github.nanomuse.*` packages, `minis://`
routes and sandbox paths remain stable. A new applicationId cannot read nanoMuse's private
storage. Use explicit supported export/import and verify what it carries.

First launch only seeds missing values: Dot, the system theme and safe preferences.
Existing SOUL.md, names, custom avatars/animations, model choices and preferences are
preserved. Same-ID upgrades require the same signing key and a higher versionCode for a
real upgrade test. P0 does not raise the current versionCode to simulate that test.

Automatic updates remain disabled until a Nanodot channel is configured. Manual checks
go to [Nanodot releases](https://github.com/Mola-maker/nanodot/releases), not upstream APKs.
Keep nanoMuse Cloud's real service identity and policies when using its integration.

## Other platforms and acceptance

Run the native build workflows against the desired pushed commit:

```sh
gh workflow run desktop-app.yml --repo Mola-maker/nanodot --ref main
gh workflow run ios-check.yml --repo Mola-maker/nanodot --ref main
```

The desktop manual run builds Windows x64, macOS arm64/x64 and Linux x64 with their own
bundled Python runtimes. Download installers and SHA256 records from the run's Artifacts.
Leave `release_tag` empty for builds only. Without signing credentials, Windows installers
are unsigned and macOS packages use ad-hoc signing rather than notarization.

The iOS workflow builds a Release archive for a generic iOS device without Apple credentials.
Its artifacts contain the unsigned `.xcarchive` and device `.app`, SHA256 records and logs;
they require valid Apple signing/provisioning before installation and are not Simulator apps
or installable IPAs. TestFlight uploads remain a separate explicitly selected workflow.

iOS, desktop (Windows/macOS/Linux), Web and relevant CI configurations use Nanodot
identities. Configuration synchronization is distinct from native packaging success:
Xcode signing/builds need macOS; Windows and macOS installers need their corresponding
platform checks. Consult the inherited [iOS](ios.md), [desktop](desktop.md) and
[Web](web.md) implementation docs with that boundary in mind.

Use the virtual Android phone for installation, launch and UI checks: offline Dot faces,
model setup, notifications, English/Chinese screens and external links. Record the virtual
device's API level, ABI and screenshots. An x86_64 chat-only emulator build does not validate
the arm64 native payloads; sandbox startup and native operation require later validation on
real arm64 hardware. Separately verify coexistence with nanoMuse, custom-data preservation
across a signed upgrade and explicit nanoMuse export/import. Each check remains pending until
its evidence is recorded; a generated APK alone does not establish installation acceptance.
The future floating ball and care mode have separate later-phase acceptance.

Retain [GPL terms](../LICENSE), [NOTICE](../NOTICE), [third-party notices](../THIRD_PARTY_NOTICES.md)
and the pinned submodule/source information with distributable builds.
