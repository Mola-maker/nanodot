# 构建 Nanodot

Nanodot 源码位于 [Mola-maker/nanodot](https://github.com/Mola-maker/nanodot)，基于 nanoMuse
基线 `f627f33ccc8ab70b9b59322929e5f15a2b1bec64` 与 OpenMinis 1.13。
Nanodot 首个预发布版本为 **1.1.0 · Crystal**，Android versionCode 为 **45**。
版本变动属于独立的发布变更。[品牌契约](brand.md) 定义统一身份。

## 工具链 {#toolchain}

使用以下固定工具链；Kotlin、AGP 或 Compose 升级属于独立变更。

| 组件 | 值 |
|---|---|
| JDK | 21 |
| Android minSdk / targetSdk / compileSdk | 26 / 35 / 36 |
| Android NDK | `27.2.12479018`（r27c） |
| SDK CMake | `3.22.1` |
| Go | 1.26 |
| Android 原生 ABI | `arm64-v8a` |
| 素材生成 | Python 3 与 Pillow |

将 `JAVA_HOME`、`ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）、`ANDROID_NDK_HOME`、`GOROOT`
与 `GOPATH` 指向本机安装路径，把 JDK、Go、gomobile 与 gobind 放到 `PATH`。
`scripts/android/env.sh` 可以辅助选择已安装工具；它的备用路径不代表这些版本已经安装。
本机路径与签名材料不要进入源码管理。

## 准备 checkout {#prepare-the-checkout}

在仓库根目录执行：

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

第二次 rebrand 应输出 `clean`。构建前检查生成差异。
`scripts/android/build-natives.sh` 准备 proot、loader、Alpine rootfs 与 `rclone.aar`；
检查完整产物，不能只看脚本是否启动。Android 不要求准备 iOS 的 iSH 构建环境。

## Debug APK {#debug-apk}

```sh
NANODOT_PYTHON="$PWD/.venv/bin/python" bash scripts/build-nanodot-apk.sh debug
```

包装脚本重新生成 Android 品牌与图标、构建原生依赖，然后运行 `:app:assembleDebug`。
它检查包身份、标签、minSdk、arm64 ABI 与签名，将 APK、SHA256、badging、签名报告与 JSON
构建元数据写入 `dist/nanodot/`。调试包文件名含 `debug`，使用调试签名。
包装脚本不会上传、打 tag 或发布。

## Release 签名 {#release-signing}

使用独立、长期保管的 Nanodot keystore，后续更新继续使用同一把密钥。仅在本机配置被 git
忽略的 `android/keystore.properties`，包含 `storeFile`、`storePassword`、`keyAlias` 与
`keyPassword`。`storeFile` 使用绝对路径可避免歧义；相对路径由 app Gradle 项目解析。
不要提交密钥或口令。

```sh
NANODOT_PYTHON="$PWD/.venv/bin/python" bash scripts/build-nanodot-apk.sh release
```

缺少签名配置或字段不完整时，release 构建失败。直接运行 Gradle 也检查签名。
release 不会回退到 debug keystore。将报告中的证书与预期密钥核对；随生成元数据记录精确工具
版本、源码 revision、工作区是否有未提交改动，以及构建日志。

## 发布已审阅的预发布版本 {#publish-a-reviewed-prerelease}

从干净的最终发布 commit 构建。在本机保留包装脚本输出的唯一候选目录和完整
`receipt.json`。发布前将源码 commit、tree、字节指纹和干净状态与已审阅源码核对；
确认 APK 版本为 **1.1.0**、versionCode 为 **45**、包名为 `io.github.nanodot.app`、
ABI 为 `arm64-v8a`，并检查证书 SHA256 与 APK SHA256。原生缓存哈希只标识字节，
应保留记录中“来源未经验证”的限制。

首版使用已审阅的手写英文及中文发布说明。旧 `scripts/release-apk.sh` 绕过候选构建记录，
并调用可能将继承的上游 PR 编号误认成 Nanodot PR 的自动说明流程。本次发布应给精确的
已审阅 commit 打 tag，手动使用 `gh release create`，传入
`--repo Mola-maker/nanodot --verify-tag --prerelease --latest=false`。附上已验证 APK、
校验文件，以及按明确白名单生成的公开构建记录：源码身份、APK 元数据/校验值、证书摘要、
工具版本和原生来源限制。公开记录不包含本机路径、环境/配置值、日志或私有签名材料。
如果重命名 APK，记录原始文件名与发布文件名的对应关系，并按发布文件名生成校验文件。
详见[维护者发布步骤](../../CONTRIBUTING.md#releasing-maintainers)。

`v*` tag 会启动桌面、harness、Docker 与常规测试构建。Release 附件需要另行在该 tag
手动运行桌面/harness 流程，并设置 `release_tag`。Tag 不会发布 Docker 镜像，也不会部署
网关、中继、网站或上传 TestFlight。这些仍是单独明确选择的操作；GitHub 预发布不能证明
它们已经执行。

## 身份、数据与更新 {#identity-data-and-updates}

Android applicationId 为 `io.github.nanodot.app`，外部 scheme 为 `nanodot://`。
namespace `com.openminis.app`、内部 `io.github.nanomuse.*` 包、`minis://` 路由与沙箱路径保持。
新的 applicationId 无法读取 nanoMuse 私有存储。使用明确支持的导出/导入流程，并核对支持范围。

首启仅初始化缺失值：Dot、系统主题与安全偏好。初始化保留已有 SOUL.md、名字、自定义头像
与动画、模型选择和偏好。同 applicationId 的升级需要相同签名与更高 versionCode。首个
专用签名版本不能覆盖调试签名构建；上游 nanoMuse 使用不同 applicationId。在记录对应
安装测试之前，不宣称兼容升级或数据保留验收已通过。

Nanodot 更新渠道配置好之前，自动更新保持关闭。手动查看
[Nanodot 发布页](https://github.com/Mola-maker/nanodot/releases)，不下载上游 APK 作为更新。
使用 nanoMuse Cloud 集成时保留其真实服务身份与政策。

## 其他平台与验收 {#other-platforms-and-acceptance}

对已推送的目标 commit 运行原生构建流程：

```sh
gh workflow run desktop-app.yml --repo Mola-maker/nanodot --ref main
gh workflow run ios-check.yml --repo Mola-maker/nanodot --ref main
```

桌面手动流程在各自 runner 构建 Windows x64、macOS arm64/x64 与 Linux x64，包含对应
平台的 Python 运行环境。安装包和 SHA256 记录从该运行的 Artifacts 下载。
`release_tag` 留空仅构建。不提供签名材料时 Windows 安装包未签名，macOS 使用 ad-hoc
签名，未完成公证。

iOS 流程无需 Apple 凭据，生成 generic iOS device 的 Release 归档。产物包括未签名
`.xcarchive`、device `.app`、SHA256 和日志；安装前需要有效的 Apple 签名及描述文件。
它们不是 Simulator 应用或可安装 IPA，TestFlight 上传仍由单独明确选择的流程执行。

iOS、桌面（Windows/macOS/Linux）、Web 与相关 CI 配置采用 Nanodot 身份。
配置同步与原生打包成功是不同结果：Xcode 签名与构建需要 macOS，Windows 和 macOS 安装包
需要对应平台检查。参考继承的 [iOS](ios.md)、[桌面](desktop.md) 与 [Web](web.md) 实现文档时，
请保留这一边界。

使用虚拟 Android 手机验证安装、启动和界面：离线 Dot 头像、模型配置、通知、中英文界面与
外部链接。记录虚拟设备的 API 级别、ABI 与截图。x86_64 仅聊天的模拟器构建不能验证 arm64
原生产物；沙箱启动与原生操作需要后续在真实 arm64 硬件上验证。另行验证与 nanoMuse 并存、
同签名升级中的自定义数据保留，以及 nanoMuse 显式导出/导入。每项检查在记录对应证据之前
仍待完成；仅生成 APK 不代表安装验收通过。Crystal 预发布包含前台悬浮球、区域聊天和
关怀引导。已有 API 35/x86_64 检查覆盖记录中的模拟器流程；完整真实 Care、语音、
arm64 真机操作和同签名升级仍需各自的验收证据。

分发构建时保留 [GPL 条款](../../LICENSE)、[NOTICE](../../NOTICE)、
[第三方署名](../../THIRD_PARTY_NOTICES.md) 与固定子模块、源码信息。

## Android 构建配置 {#android-workflow-profiles}

先 source 已有的工具链环境，或通过 `NANODOT_ENV_FILE` 指定该文件。
入口在预检前读取它和 `scripts/android/env.sh`，不会安装或修复工具链。
`doctor` 只读取 JDK 21、Go 1.26（仅本地工具链）、SDK API 36、build-tools 35.0.0、
NDK 27.2.12479018 和 CMake 3.22.1；不运行 Gradle。

```sh
bash scripts/build-nanodot-apk.sh doctor
bash scripts/build-nanodot-apk.sh check
bash scripts/build-nanodot-apk.sh debug-x86_64 --with-test
bash scripts/build-nanodot-apk.sh debug
bash scripts/build-nanodot-apk.sh release
```

`check` 使用 x86_64 运行 debug 单元测试；`debug-x86_64` 跳过 arm64 原生准备，
构建仅聊天的 APK。两个 debug 配置都可加 `--with-test`，在同一次 Gradle 调用中
生成 instrumentation APK；不会执行设备测试。不启用语音诊断 flag。
Release 必须使用专用签名配置。

x86_64 配置会排除缓存中的 ARM proot 与 Alpine rootfs 资源，包括在同一工作树中先构建
arm64 的情况。默认的隐藏文件过滤规则仍保留；切换配置无需删除原生缓存。arm64 配置继续
包含沙箱资源。

每个 APK 候选保存在唯一的 `dist/nanodot/<commit>-<profile>-<ABI>-<unique>/`
目录和 `receipt.json` 中。记录包括 commit、tree、未提交状态、源码字节指纹、
请求的任务、ABI flag、从 APK 读取的包名/版本/SDK/ABI、主包和可选测试包 SHA256、
证书摘要及实际工具链输出。构建期间源码变化会拒绝候选。已有原生输入记录哈希，
明确标注来源未经验证；文件存在不能证明已重新构建 arm64 原生组件。
主包和测试包证书不一致会拒绝该组合。应一起保留两个包和记录；SHA256 标识安装的 APK 字节。

原 debug/release APK、SHA256、badging、signature 和 `build-<mode>.json` 路径保留，
供 CI 使用。这些兼容文件会在下次构建时覆盖；保留候选请使用唯一目录。
构建成功本身不能证明安装、instrumentation 执行、arm64 原生行为或 release lint 通过。

默认 `check` 覆盖完整 debug 单元测试集。若明确只检查本地 nanoMuse 回归范围，可用
`NANODOT_TEST_SCOPE=nanomuse bash scripts/build-nanodot-apk.sh check`；
这不代表完整测试集通过，不会静默过滤失败。可选且有界的环境设置包括
`NANODOT_OFFLINE=1`（Gradle 离线）、`NANODOT_JVM_HEAP_MB=4096`（512–32768 MiB）
和 `NANODOT_KOTLIN_IN_PROCESS=1`。它们直接转成 Gradle 参数，不使用 eval；
APK 记录包含这些选项。Instrumentation APK 可以没有版本字段；记录使用 null，
文件名采用配对主 APK 的版本。

所有配置都显式传入并记录 `-Pnm.manualVoiceProof=false`，避免继承的 Gradle 属性
启用语音诊断实现。兼容 JSON 的 `file` 指向同目录 APK；`candidateReceipt`
指向包含配对证据的正式记录。

Android CI 使用同一 `debug --with-test` 入口构建 arm64 主包/测试包组合，
上传 `dist/nanodot/`。随后使用 x86_64 运行显式
`NANODOT_TEST_SCOPE=nanomuse` 命名空间范围的 `check`。配对 APK 构建已编译
instrumentation 源码；CI 不在设备上执行它们。该局部 CI 结果不能证明
完整上游单元测试集通过。
