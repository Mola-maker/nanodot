---
layout: home
title: Nanodot 文档
hero:
  name: Nanodot
  text: Dot，开源个人智能体。
  tagline: P0 建立 Nanodot 品牌、离线 Dot 头像与安全默认值，基于 nanoMuse 和 OpenMinis。从源码构建；设备验收与各平台原生打包需要独立验证。
  actions:
    - theme: brand
      text: 构建 P0
      link: /zh/build
    - theme: alt
      text: 品牌契约
      link: /zh/brand
    - theme: alt
      text: GitHub 源码
      link: https://github.com/Mola-maker/nanodot
---

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
