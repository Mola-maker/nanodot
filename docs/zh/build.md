# 构建 Nanodot P0

Nanodot 源码位于 [Mola-maker/nanodot](https://github.com/Mola-maker/nanodot)，基于 nanoMuse
基线 `f627f33ccc8ab70b9b59322929e5f15a2b1bec64` 与 OpenMinis 1.13。
P0 沿用版本 **1.0.1** 和 Android versionCode **44**。版本变动属于独立的发布变更。
[品牌契约](brand.md) 定义统一身份。

## 工具链 {#toolchain}

使用以下 P0 工具链；品牌修改不顺带升级 Kotlin、AGP 或 Compose。

| 组件 | P0 值 |
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

## 身份、数据与更新 {#identity-data-and-updates}

Android applicationId 为 `io.github.nanodot.app`，外部 scheme 为 `nanodot://`。
namespace `com.openminis.app`、内部 `io.github.nanomuse.*` 包、`minis://` 路由与沙箱路径保持。
新的 applicationId 无法读取 nanoMuse 私有存储。使用明确支持的导出/导入流程，并核对支持范围。

首启仅初始化缺失值：Dot、系统主题与安全偏好。已有 SOUL.md、名字、自定义头像与动画、模型
选择和偏好保留。同 applicationId 的升级测试需要相同签名与更高 versionCode。
P0 不为模拟测试而提高当前 versionCode。

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
仍待完成；仅生成 APK 不代表安装验收通过。未来的新悬浮球与关怀模式另按后续阶段验收。

分发构建时保留 [GPL 条款](../../LICENSE)、[NOTICE](../../NOTICE)、
[第三方署名](../../THIRD_PARTY_NOTICES.md) 与固定子模块、源码信息。
