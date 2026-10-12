---
layout: home
title: Nanodot 文档
hero:
  name: Nanodot
  text: Dot，开源个人智能体。
  tagline: 使用自己的模型，通过对话、工具、记忆和定时工作完成设备上的任务。Nanodot 基于 nanoMuse 与 OpenMinis，目前是源码预览。
  actions:
    - theme: brand
      text: 构建 Android
      link: /zh/build
    - theme: alt
      text: 快速开始
      link: https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md#快速开始
    - theme: alt
      text: GitHub 源码
      link: https://github.com/Mola-maker/nanodot
---

## 从一个小任务开始 {#start-with-a-small-task}

从本仓库安装 Python 运行时、配置模型后，可以体验文字对话或本地 Web 控制台。
[README 快速开始](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md#快速开始)
说明了安装步骤；你也可以为 Android 8+、ARM64 设备[构建 Android App](build.md)。

例如，让 Dot 创建一份旅行清单并保存为文件。逐步添加工具时，请了解
[配置](configuration.md)、[权限](sentinel.md)与[隐私](privacy.md)。如果愿意参与，
可以[反馈可复现的结果](https://github.com/Mola-maker/nanodot/issues/new/choose)，或阅读
[贡献指南](../../CONTRIBUTING.md)。

## 当前范围 {#current-scope}

P0 沿用版本 **1.0.1** / Android versionCode **44**。Dot 使用离线静态头像；已有名字、
SOUL.md、自定义头像与动画、模型和偏好保留。主题跟随系统，连续语音与自动更新默认关闭。
新悬浮球与关怀交互属于后续阶段。

## 构建与下载 {#build-and-downloads}

[构建指南](build.md) 说明固定工具链与 `scripts/build-nanodot-apk.sh debug|release`。
release 必须使用独立签名，不回退到 debug 密钥；包装脚本不会发布。本页不声称已经存在可安装的
Nanodot 发布包，也不声称真机安装或同签名升级验收通过。

[Nanodot 发布页](https://github.com/Mola-maker/nanodot/releases) 是未来发布包的地址。
上游 nanoMuse APK、桌面安装包、演示与 TestFlight 构建属于另一个产品，不是 Nanodot 下载。

## 继承文档与服务 {#inherited-documentation-and-services}

大多数平台和运行时页面保留 nanoMuse 实现说明与历史引用。请结合
[Nanodot P0 构建边界](build.md#other-platforms-and-acceptance) 阅读。跨平台配置已同步；
Xcode 和 Windows/macOS 原生打包仍需各自平台验证。

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
