# Nanodot 开发快照

快照日期：2026-10-11。Android 仍是交付首要目标。本文记录当前源码分支和实际验证结果，并非发布或完整验收报告。

## 源码分支 {#source-branches}

| 范围 | 分支 | 冻结源码 |
|---|---|---|
| Android 整合 | [nanodot/android-dot-care](https://github.com/Mola-maker/nanodot/tree/nanodot/android-dot-care) | `bbfdc469` |
| 语音资源归属，定向复审通过 | [nanodot/android-dot-voice](https://github.com/Mola-maker/nanodot/tree/nanodot/android-dot-voice) | `94cdbb0c` |
| 关怀原生测试工具，源码审查通过 | [nanodot/care-native-acceptance](https://github.com/Mola-maker/nanodot/tree/nanodot/care-native-acceptance) | `c0dea86b` |
| 桌面 UI 候选 | [nanodot/ui-desktop](https://github.com/Mola-maker/nanodot/tree/nanodot/ui-desktop) | `88c542b2` |
| iOS UI 候选 | [nanodot/ios-native](https://github.com/Mola-maker/nanodot/tree/nanodot/ios-native) | `805923c3` |
| 设计系统 | [nanodot/ui-system](https://github.com/Mola-maker/nanodot/tree/nanodot/ui-system) | `128cfb2f` |
| 网关与网页源码 | [nanodot/workers-ai-site](https://github.com/Mola-maker/nanodot/tree/nanodot/workers-ai-site) | `06812a7e` |
| 关怀规划器 | [nanodot/care-planner](https://github.com/Mola-maker/nanodot/tree/nanodot/care-planner) | `0fe9df1e` |
| 关怀控制器 | [nanodot/care-controller](https://github.com/Mola-maker/nanodot/tree/nanodot/care-controller) | `cd252169` |
| 关怀聚焦 UI | [nanodot/care-focus-ui](https://github.com/Mola-maker/nanodot/tree/nanodot/care-focus-ui) | `af692ef1` |
| Android API 验证工具 | [nanodot/android-api-proof](https://github.com/Mola-maker/nanodot/tree/nanodot/android-api-proof) | `a921f939` |
| Android 日志安全 | [nanodot/android-log-safety](https://github.com/Mola-maker/nanodot/tree/nanodot/android-log-safety) | `be882776` |
| 较早的 Android 交互源码 | [nanodot/android-dot-care-work](https://github.com/Mola-maker/nanodot/tree/nanodot/android-dot-care-work) | `44fb0c3b` |
| 历史悬浮聊天原生测试候选 | [nanodot/android-dot-ui-acceptance](https://github.com/Mola-maker/nanodot/tree/nanodot/android-dot-ui-acceptance) | `428c2574` |

默认分支仍是较早的 P0 快照 `7ed8ce6e`。发布这些开发分支保留各自审查边界，并未将其内容合并到默认分支。

## Android 实际观察 {#android-observations}

- 已安装的整合 APK 基于 `bbfdc469`，SHA256 为 `f5fc54e49dc67adb0ae67ece6a80a9da8755b09d7a5faff7804f6311048ed825`。它在现有 API 35 虚拟手机上构建、安装通过。此前产品回归包括 45 项及另一次定向 23 项。
- `94cdbb0c` 的语音 fix1 完整、未过滤的主 Kotlin 编译通过，51 项定向 JVM 测试无失败、错误或跳过。独立定向复审批准了管道资源退役、自动结束通知及语言重启归属三项修复；修复前的八个不同失败反例已保存。该候选尚未合并或安装。
- 较早的完整 JVM 测试共 1,753 项，42 项失败，原日志与 XML 保留。未执行基线完整对照来证明全部 42 项均为基线失败，也不声称全套通过。
- 关怀原生测试工具编译、独立审查通过，使用匹配的 debug 签名后已安装测试 APK。实际宿主启动检查失败于窗口焦点观测，三个测试用例与视觉验收尚未执行；之后唯一一次恢复通过限定状态恢复校验。原失败仍记为失败。
- 独立诊断发现，实际 API 35 的 `dumpsys window windows` 输出没有所需焦点字段，`dumpsys window displays` 提供了这些字段；原停止校验也确实观察到正在退出的窗口。另一个控制器候选正在修正观测方式并复用已安装 APK，新的执行仍待审查。本地正在修改的工具未纳入此源码快照。
- 较早的悬浮聊天原生验收失败，包括测试宿主缺少 Kotlin 运行时。完整发送、流式展示及同会话完成仍未验收；源码批准与恢复通过不代表 UI 成功。
- 此前 Android provider 流式请求与关怀规划器的真实 API 返回 HTTP 200。真实麦克风、ASR/TTS、语音权限与前台服务及 UI 接入、圈选、完整关怀引导和其余界面与设备矩阵仍待完成。

## 其他交付关卡 {#other-delivery-gates}

桌面和 iOS 候选保留独立分支。本地桌面 UI 检查与 iOS 静态检查分别有记录，原生平台验收仍需完成。网关与网页已有源码，但 `molamaker.si` 部署、Cloudflare Workers AI 接入及免费额度限制尚未完成。此前 P0 的 GitHub Actions 构建不能用于证明这些新候选。

已向上游提出并回读核验 11 个 issue：#332–340、#347，以及 [#356：Provider 取消与 VAD 录音](https://github.com/nano-muse/nanoMuse/issues/356)。#356 中的受控测试不代表原生麦克风验证。

仅同步已提交源码和此公开进度。本地 `.env`、API 与 Cloudflare 凭据、签名密钥、虚拟手机私有基线和剪贴板内容保留在本地。
