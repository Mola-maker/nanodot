# Nanodot <version> · <Codename>

<!-- English first; the Chinese version carries the same facts in the collapsed block below.
     Codenames so far: Foundation, Identity, Home, Guardrails, Memory, Avatar, Welcome, Polish,
     Portrait, Motion, Hatch, Hands, Reach, Home, Stage, Palette, Open, Ensemble, Presence,
     Footing, Commons, Welcome, Signal, Mirror, Window, Ledger, Harness, Likeness, Rooms, Locks,
     Union, Steps, Turns, Accord, Thread, Weave, Loom, Keys, Clear, Choice, Keel, Ballast, Crystal (CHANGELOG.md has the list).
     Choose a new one-word English codename. Earlier names belong to inherited nanoMuse releases.
     The first Nanodot release is 1.1.0 Crystal, versionCode 45, published as a prerelease.
     Write and review the notes by hand: inherited upstream PR numbers must not be resolved
     against Nanodot by scripts/release_notes.py. Add only verified links and actual attachments.
     Report build, emulator, fixture, real-provider and physical-device evidence separately.
     Keep voice plain and specific. Replace the placeholders before publishing. -->

**Nanodot `<version>` “<Codename>”** is a prerelease of <one sentence describing the changes>.

<One or two short paragraphs explaining what changed for the person using it.>

## Highlights

- **<Feature>.** <What changed and where to find it.>
- **Verification.** <Name the tested commit/candidate and actual checks, including device API/ABI when applicable.>
- **Still pending.** <List the unverified user flows. For Crystal, retain complete real Care, voice, physical arm64-device operation and signed-upgrade acceptance as pending.>

## Upgrade Notes

- **Android package.** `<published-apk-name>.apk` targets Android 8.0+ and arm64, with applicationId `io.github.nanodot.app`, version `<version>` and versionCode `<n>`. It uses the dedicated Nanodot release key. Verify it with `sha256sum -c <published-apk-name>.apk.sha256`; the attached public build receipt records its source identity, SHA256, certificate digest and build limitations.
- **Install and data boundary.** This first dedicated-key release cannot replace a debug-signed build. Upstream nanoMuse uses a different applicationId and private storage. Future same-ID updates require the same release key and a higher versionCode. Do not promise an upgrade or data-preservation result without the corresponding installation evidence.
- **Acceptance boundary.** An arm64 package and a successful emulator check do not establish physical-device or complete Care/voice acceptance. Cached native-input hashes identify bytes; preserve any unverified-provenance limitation from the receipt.
- **Available downloads.** <List only the actual APK, checksum, public receipt and any other verified attachments on this release. For desktop artifacts, state actual signing/notarization and validation status. An unsigned iOS archive is not an installable IPA.>
- **Other platforms and services.** Tags start builds; desktop and harness attachments require separate manual workflow runs. This release does not automatically deploy the gateway, relay or website, publish Docker images, or upload to TestFlight. Upstream download mirrors and TestFlight builds are separate products.

## Community

Report Nanodot issues and contributions in [Mola-maker/nanodot](https://github.com/Mola-maker/nanodot). When using nanoMuse Cloud, its availability, account and data policies remain the upstream service provider's; Nanodot does not operate that service or promise a free allowance.

Nanodot derives from [nanoMuse](https://github.com/nano-muse/nanoMuse) and [OpenMinis](https://github.com/OpenMinis/OpenMinis) 1.13. The complete corresponding source is [tag `v<version>`](https://github.com/Mola-maker/nanodot/tree/v<version>) and its pinned submodules, including `android/deps/proot`. Retain [LICENSE](https://github.com/Mola-maker/nanodot/blob/v<version>/LICENSE), [NOTICE](https://github.com/Mola-maker/nanodot/blob/v<version>/NOTICE) and [third-party notices](https://github.com/Mola-maker/nanodot/blob/v<version>/THIRD_PARTY_NOTICES.md). The repository is GPL-3.0-or-later. Nanodot is independent of Meta and its Muse trademark.

<Add reviewed Nanodot contributor acknowledgements and source-comparison links when available. Preserve upstream attribution without presenting upstream PRs as Nanodot PRs.>

<details>
<summary>简体中文</summary>

**Nanodot `<version>`「<Codename>」** 是预发布版本，<一句话说明本次变更>。

<一两段话，说明这些变化对使用者意味着什么，与英文事实一致。>

### 亮点

- **<功能>。** <发生了什么变化，在哪里使用。>
- **验证结果。** <列明已测试的 commit/候选及实际检查；涉及设备时注明 API/ABI。>
- **仍待验收。** <列出未验证的用户流程。Crystal 仍须保留完整真实 Care、语音、arm64 真机操作与同签名升级验收待完成。>

### 升级说明

- **Android 安装包。** `<published-apk-name>.apk` 面向 Android 8.0+、arm64，applicationId 为 `io.github.nanodot.app`，版本为 `<version>`，versionCode 为 `<n>`。使用专用 Nanodot 发布密钥。运行 `sha256sum -c <published-apk-name>.apk.sha256` 校验；附带的公开构建记录包含源码身份、SHA256、证书摘要与构建限制。
- **安装与数据边界。** 首个专用签名版本不能覆盖调试签名构建。上游 nanoMuse 使用不同 applicationId 和私有存储。今后同 applicationId 更新需要相同发布密钥与更高 versionCode。没有对应安装证据时，不承诺升级或数据保留结果。
- **验收边界。** arm64 安装包和模拟器检查通过不能证明真机或完整 Care/语音验收通过。原生缓存哈希只标识字节；应保留构建记录中来源未经验证的限制。
- **可下载内容。** <仅列出本次实际附加且验证过的 APK、校验文件、公开构建记录及其他产物。桌面产物说明实际签名/公证与验证状态。未签名 iOS 归档不是可安装 IPA。>
- **其他平台与服务。** Tag 会启动构建；桌面和 harness 附件需要另行手动运行流程。本次发布不会自动部署网关、中继或网站、发布 Docker 镜像或上传 TestFlight。上游下载镜像与 TestFlight 构建属于其他产品。

### 社区

请在 [Mola-maker/nanodot](https://github.com/Mola-maker/nanodot) 报告 Nanodot 问题或参与贡献。使用 nanoMuse Cloud 时，可用性、账号与数据政策仍由上游服务提供方负责；Nanodot 不运营该服务，也不承诺免费额度。

Nanodot 源于 [nanoMuse](https://github.com/nano-muse/nanoMuse) 和 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 1.13。完整对应源码为 [tag `v<version>`](https://github.com/Mola-maker/nanodot/tree/v<version>) 及其固定子模块，包括 `android/deps/proot`。分发时保留 [LICENSE](https://github.com/Mola-maker/nanodot/blob/v<version>/LICENSE)、[NOTICE](https://github.com/Mola-maker/nanodot/blob/v<version>/NOTICE) 与[第三方署名](https://github.com/Mola-maker/nanodot/blob/v<version>/THIRD_PARTY_NOTICES.md)。仓库以 GPL-3.0-or-later 发布。Nanodot 独立于 Meta 及其 Muse 商标。

<如有可验证信息，添加与英文一致的 Nanodot 贡献者致谢和源码比较链接。保留上游署名，不将上游 PR 当作 Nanodot PR。>

</details>
