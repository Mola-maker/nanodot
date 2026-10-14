---
layout: home
title: Nanodot 文档
hero:
  name: Nanodot
  text: Dot，开源个人智能体。
  tagline: Android 预览版 1.1.0 Crystal 带来水晶悬浮球、前台对话、圈选提问与 Care 关怀引导，基于 nanoMuse 和 OpenMinis。
  actions:
    - theme: brand
      text: 下载 Android 预览版
      link: https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0
    - theme: alt
      text: 发布说明
      link: /releases/v1.1.0
    - theme: alt
      text: GitHub 源码
      link: https://github.com/Mola-maker/nanodot
---

## Android 预发布 · 1.1.0 Crystal {#current-scope}

这是 Nanodot 的首个 Android 预发布，适用于 **Android 8.0+ / ARM64**，版本 **1.1.0**
（versionCode **45**）。从本仓库下载 [`Nanodot-1.1.0-arm64.apk`](https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk)
和 [SHA-256 文件](https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk.sha256)。
APK 使用 Nanodot 专用发行密钥签名；替换使用不同签名的开发包前，请先阅读[发布说明](../releases/v1.1.0.md)。

水晶 Dot 可打开蓝色玻璃面板中的前台对话。圈选部分屏幕，检查本地预览，再发送问题；明确配置为
支持图像的模型会收到遮罩后的图片，其他模型使用选区内可获取的文字。Care 显示目标与操作提示，
将完整指令保留在对话中，并提供步骤确认与 Stop。

主题跟随系统；自定义名字、SOUL.md、头像、动画、模型与偏好保留。连续语音与自动更新默认关闭。

## Android 实际截图 {#android-captures}

<table>
  <tr><th>Nanodot 首页</th><th>Care 对话</th></tr>
  <tr><td><img src="../superpowers/assets/2026-10-14-main-care/home-welcome.png" alt="API 35 模拟器中实际捕获的 Nanodot 欢迎页" width="260"></td><td><img src="../superpowers/assets/2026-10-14-main-care/care-transcript.png" alt="受控测试页面上保留在对话中的 Care 指令" width="260"></td></tr>
</table>

以上是此前开发候选包在 API 35 模拟器上的实际截图。Care 示例使用受控测试页面与本地 planner。
这些截图不代表本次签名 1.1.0 APK 已重新完成运行验收；各 APK 身份与测试范围见
[验收记录](android-verification.md#main-care-trace-2026-10-14)。

## 预发布边界与源码构建 {#build-and-downloads}

真实模型驱动的完整 Care 任务成功闭环、任意第三方应用、ARM64 真机、较旧 Android 版本、中文
麦克风识别与可听语音仍需更广泛验收。[受控图片测试](android-verification.md#raster-region-live-2026-10-13)
的核心结果为 4/4 正确，但完整回答仅 2/4 未发现错误。当前 iOS 和桌面端运行验收仍需独立完成。

[构建指南](build.md)说明固定工具链与 `scripts/build-nanodot-apk.sh debug|release`。
release 构建必须使用独立签名，不回退到 debug 密钥；包装脚本不会发布。Nanodot 与 nanoMuse
使用独立私有存储，数据迁移需要显式导出/导入；同 applicationId 升级还需要相同签名和更高 versionCode。

## 从一个小任务开始 {#start-with-a-small-task}

从本仓库安装 Python 运行时、配置模型后，可以体验文字对话或本地 Web 控制台。
[README 快速开始](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md#快速开始)
说明了安装步骤；你也可以为 Android 8+、ARM64 设备[构建 Android App](build.md)。

例如，让 Dot 创建一份旅行清单并保存为文件。逐步添加工具时，请了解
[配置](configuration.md)、[权限](sentinel.md)与[隐私](privacy.md)。如果愿意参与，
可以[反馈可复现的结果](https://github.com/Mola-maker/nanodot/issues/new/choose)，或阅读
[贡献指南](../../CONTRIBUTING.md)。

## 继承文档与服务 {#inherited-documentation-and-services}

大多数平台和运行时页面保留 nanoMuse 实现说明与历史引用。上游 nanoMuse APK、桌面安装包、演示
与 TestFlight 构建属于另一个产品。Nanodot 下载位于 [Nanodot 发布页](https://github.com/Mola-maker/nanodot/releases)。
请结合[构建与验收说明](build.md#other-platforms-and-acceptance)阅读各平台的能力描述。

**nanoMuse Cloud** 仍为上游服务，保留真实名称、端点与政策。
Nanodot 不声称运营该服务，不承诺免费额度，也不继承非营利身份。
可以使用自己的服务商，或明确配置已有集成。

## 上游论文 {#upstream-paper}

以下引用属于 nanoMuse，作为上游署名保留。

## 引用

报告《nanoMuse: An Open-Source Personal Agent for Every Device You Own》在 arXiv 上，编号 [2610.08699](https://arxiv.org/abs/2610.08699)：什么是个人智能体，Muse 是怎么搭的，nanoMuse 又是怎样用开源的方式回应的。如果 nanoMuse 对你有帮助，欢迎引用我们的论文。

```bibtex
@misc{liu2026nanomuseopensourcepersonalagent,
      title={nanoMuse: An Open-Source Personal Agent for Every Device You Own}, 
      author={Guangyi Liu and Yong Liu and Jiangning Zhang},
      year={2026},
      eprint={2610.08699},
      archivePrefix={arXiv},
      primaryClass={cs.AI},
      url={https://arxiv.org/abs/2610.08699}, 
}
```
