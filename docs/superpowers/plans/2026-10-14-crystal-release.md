# Nanodot 1.1.0 Crystal Release Plan

> **For agentic workers:** Use the repository release recipe and Superpowers verification-before-completion. The coordinator owns builds and publication; independent agents review disjoint documentation and release evidence.

**Goal:** Publish the current Nanodot Android functionality as version 1.1.0 Crystal, a GitHub prerelease with an independently signed arm64 APK and verifiable source/artifact identity.

**Architecture:** Keep production behavior unchanged. Synchronize application versions, replace obsolete P0 repository copy, then build from the exact clean release commit and publish inspected assets. Keep the relay, website deployment and account configuration frozen.

**Tech stack:** Existing Android build wrapper, JDK 21, Android SDK 36 / build-tools 35.0.0, NDK r27c, GitHub CLI, VitePress.

**Spec:** User request on 2026-10-14: build, improve the GitHub repository and publish a release; repository requirements in AGENTS.md and CONTRIBUTING.md.

## Constraints and review focus

- Use 1.1.0 / versionCode 45; relay version remains unchanged. Preserve upstream attribution and internal package identities.
- First Nanodot release needs its own durable signing key; do not claim compatibility with prior debug signatures or upstream APKs.
- The public receipt must identify the exact source and APK without local paths, credentials or signing configuration.
- Existing native evidence retains its original APK identity. New release build inspection does not establish real-device or full-provider Care acceptance.
- Publishing this GitHub prerelease does not deploy the gateway, relay, TestFlight, website or Docker images.

## Tasks

- [ ] Synchronize versions with release-bump/rebrand/release-docs; second rebrand must report clean. Update current READMEs, docs homepages, release notes, changelog and maintainers' release instructions.
- [ ] Review the documentation independently; run release/branding/version tests, catalogue checks and VitePress build. Commit with DCO sign-off and integrate through a dedicated release PR after required checks.
- [ ] Build the exact clean release commit with `scripts/build-nanodot-apk.sh release`. Inspect applicationId, version/code, ABI, signature, native/cache provenance, APK checksum and source receipt.
- [ ] Create tag v1.1.0 at that commit, publish a prerelease with the inspected APK, checksums and a sanitized public build receipt. Verify the published assets by re-downloading and hashing them.
- [ ] Confirm tag/release/commit agreement, link the release and APK, and retain explicit limits on real-provider Care completion, voice, physical devices and other platforms.

Execution record and private build receipts are retained outside the repository in this task's release output directory. Only sanitized evidence is published.
