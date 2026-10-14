# Build Nanodot

Nanodot source is [Mola-maker/nanodot](https://github.com/Mola-maker/nanodot), based on
nanoMuse baseline `f627f33ccc8ab70b9b59322929e5f15a2b1bec64` and OpenMinis 1.13.
The first Nanodot prerelease is **1.1.0 · Crystal**, Android versionCode **45**.
Version changes belong to a separate release change. The [brand contract](brand.md)
defines the shared identity.

## Toolchain

Use this fixed toolchain; Kotlin, AGP and Compose upgrades belong to a separate change.

| Component | Value |
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

## Publish a reviewed prerelease

Build from the clean final release commit. Keep the unique candidate directory printed by
the wrapper and its full `receipt.json` locally. Before publishing, match the source commit,
tree, fingerprint and clean state to the reviewed source; check APK version **1.1.0**,
versionCode **45**, package `io.github.nanodot.app`, ABI `arm64-v8a`, certificate SHA256
and APK SHA256. Native-cache hashes identify bytes; retain the receipt's unverified
provenance limitation.

The first release uses reviewed handwritten English and Chinese notes. Legacy
`scripts/release-apk.sh` bypasses this candidate receipt and invokes an automatic notes
path that can confuse inherited upstream PR numbers with Nanodot PRs. For this release,
tag the exact reviewed commit and use `gh release create` manually with
`--repo Mola-maker/nanodot --verify-tag --prerelease --latest=false`. Attach the verified
APK, checksum and an explicitly allowlisted public receipt containing source identity,
APK metadata/checksum, certificate digest, tool versions and native provenance limits.
Keep local paths, environment/configuration values, logs and private signing material
out of the public receipt. If the APK is renamed, record the original-to-published filename
mapping and write the checksum against the published filename. See the
[maintainer recipe](../CONTRIBUTING.md#releasing-maintainers).

A `v*` tag starts desktop, harness, Docker and general test builds. Release attachments
require separate manual desktop/harness runs at that tag with `release_tag` set. Tags do
not publish Docker images or deploy the gateway, relay, website or TestFlight. Those
remain separate explicit operations; a GitHub prerelease is not evidence they occurred.

## Identity, data and updates

Android applicationId is `io.github.nanodot.app`; the external scheme is `nanodot://`.
The namespace `com.openminis.app`, internal `io.github.nanomuse.*` packages, `minis://`
routes and sandbox paths remain stable. A new applicationId cannot read nanoMuse's private
storage. Use explicit supported export/import and verify what it carries.

First launch only seeds missing values: Dot, the system theme and safe preferences.
Existing SOUL.md, names, custom avatars/animations, model choices and preferences are
preserved by initialization. Same-ID upgrades require the same signing key and a higher
versionCode. The first dedicated-key release cannot replace a debug-signed build; upstream
nanoMuse has a different applicationId. No compatible upgrade or data-preservation result
is claimed until the relevant installation test has been recorded.

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
The Crystal prerelease includes the foreground orb, region chat and Care guidance.
Existing API 35/x86_64 checks cover the documented emulator flows; complete real Care,
voice, physical arm64-device operation and signed upgrades still need their own evidence.

Retain [GPL terms](../LICENSE), [NOTICE](../NOTICE), [third-party notices](../THIRD_PARTY_NOTICES.md)
and the pinned submodule/source information with distributable builds.

## Android workflow profiles {#android-workflow-profiles}

Source your existing toolchain environment, or pass its path with `NANODOT_ENV_FILE`.
The wrapper sources that file and `scripts/android/env.sh` before preflight. It never
installs or repairs toolchains. `doctor` reads JDK 21, Go 1.26 (local toolchain only),
SDK API 36, build-tools 35.0.0, NDK 27.2.12479018 and CMake 3.22.1; it does not run Gradle.

```sh
bash scripts/build-nanodot-apk.sh doctor
bash scripts/build-nanodot-apk.sh check
bash scripts/build-nanodot-apk.sh debug-x86_64 --with-test
bash scripts/build-nanodot-apk.sh debug
bash scripts/build-nanodot-apk.sh release
```

`check` runs debug unit tests with x86_64; `debug-x86_64` skips the arm64 native
preparation and builds a chat-only APK. `--with-test` adds the instrumentation APK
in the same Gradle invocation for either debug profile. It does not run device tests.
No diagnostic voice flag is enabled. Release requires the dedicated signing configuration.

The x86_64 profile excludes cached ARM proot and Alpine rootfs assets, including after
an arm64 build in the same checkout. It retains the default hidden-file asset filters;
switching profiles does not require deleting the native cache. The arm64 profiles keep
their sandbox assets.

Each APK candidate lives in a unique `dist/nanodot/<commit>-<profile>-<ABI>-<unique>/`
folder with `receipt.json`. It records the commit, tree, dirty state, source-byte
fingerprint, requested tasks, ABI flag, inspected APK package/version/SDK/ABIs,
main and optional test SHA256, certificate digest and actual toolchain output.
Source changes during the build reject the candidate. Existing native inputs are
hashed with explicitly unverified provenance; their presence does not prove a fresh
arm64 native build. Main/test certificate mismatch rejects the pair. Keep the pair
and receipt together; SHA256 identifies the APK bytes used for installation.

The original debug/release APK, SHA256, badging, signature and `build-<mode>.json`
paths remain available for CI. Those compatibility files are overwritten on the
next build; use the unique folder to retain a candidate. Builds alone do not prove
installation, instrumentation execution, native arm64 behavior or release lint.

The default `check` covers the full debug unit suite. For the explicitly narrower
local nanoMuse regression scope, use `NANODOT_TEST_SCOPE=nanomuse bash
scripts/build-nanodot-apk.sh check`; this is not a full-suite pass. No failures are
silently filtered. Optional bounded environment settings are `NANODOT_OFFLINE=1`
(Gradle offline), `NANODOT_JVM_HEAP_MB=4096` (512–32768 MiB), and
`NANODOT_KOTLIN_IN_PROCESS=1`. These become literal Gradle arguments without eval;
APK receipts record the options. Instrumentation APKs may have no version fields;
the receipt records null and their filenames use the paired main APK's version.

Every profile explicitly passes `-Pnm.manualVoiceProof=false` and records it, so inherited
Gradle properties cannot enable the diagnostic voice implementation. Compatibility JSON
`file` resolves beside that JSON; `candidateReceipt` links to the canonical paired receipt.

Android CI uses the same `debug --with-test` entry to assemble the arm64 main/test
pair and uploads `dist/nanodot/`. It then runs `check` with the explicit
`NANODOT_TEST_SCOPE=nanomuse` namespace scope using x86_64. The paired APK build
already compiles instrumentation sources; CI does not execute them on a device.
This scoped CI result is not evidence that the full upstream unit suite passes.
