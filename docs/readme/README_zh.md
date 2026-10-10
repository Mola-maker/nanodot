# Nanodot

<p align="center"><img src="../../assets/brand/nanodot-mark.svg" alt="Nanodot · Dot" width="112" height="112"></p>

[English](https://github.com/Mola-maker/nanodot/blob/main/README.md) | [简体中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md) | [繁體中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh-TW.md) | [Español](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_es.md) | [Français](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_fr.md) | [Bahasa Indonesia](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_id.md) | [日本語](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ja.md) | [한국어](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ko.md) | [Русский](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ru.md) | [Tiếng Việt](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_vi.md)

Nanodot 是基于 [nanoMuse](https://github.com/nano-muse/nanoMuse) 的开源个人智能体；上游手机端基于 [OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis)。默认助手叫 **Dot**。仓库包含 Android、iOS、桌面外壳、Web 控制台和 Python 运行时。

## P0 范围

P0 建立 Nanodot 品牌、统一品牌配置、离线 Dot 素材和安全默认值。新悬浮球与关怀模式属于后续阶段；当前偏好键不会启用这些能力，也不会授予无障碍权限。

主题默认跟随系统。Dot 内置五态静态头像；自定义名字、SOUL.md、头像、动画、模型与偏好保留。连续语音默认关闭，自动更新关闭；请手动查看本仓库发布页。

沿用版本 **1.0.1**，Android versionCode 为 **44**。各平台构建配置已同步；真机安装与升级验收仍待完成。Linux 构建不能证明 Xcode 或 Windows/macOS 原生打包通过。

## 构建与安装

从当前 checkout 本地构建 Android。debug 与 release 包分开；release 必须使用独立签名，不会回退到 debug 密钥。构建脚本不会发布、上传或打 tag。

```sh
bash scripts/build-nanodot-apk.sh debug
# Dedicated local signing configuration is required for release.
bash scripts/build-nanodot-apk.sh release
```

参见[构建指南](../zh/build.md)、[品牌契约](../zh/brand.md)与[项目文档](../zh/index.md)。Nanodot 安装包发布后应位于 [Nanodot 发布页](https://github.com/Mola-maker/nanodot/releases)。上游 nanoMuse 下载与 TestFlight 构建属于另一个产品。

Android applicationId 为 `io.github.nanodot.app`，外部 scheme 为 `nanodot://`。内部 `com.openminis.app`、`io.github.nanomuse.*` 包、`minis://` 路由与沙箱路径保留。新 applicationId 使用独立私有存储；nanoMuse 数据需要显式导出/导入，不能自动读取旧应用文件。

## 模型与服务

可以使用自己的模型服务商，或配置已有中继集成。**nanoMuse Cloud** 保留上游服务的名称与端点；Nanodot 不声称运营该服务，也不承诺免费额度。账号、可用性与数据政策由该服务提供方决定。

## 贡献与许可

此 fork 的问题请提交到 [Nanodot issues](https://github.com/Mola-maker/nanodot/issues)。阅读 [CONTRIBUTING.md](../../CONTRIBUTING.md) 与 [AGENTS.md](../../AGENTS.md)，提交需带 DCO sign-off。许可为 [GPL-3.0-or-later](../../LICENSE)，上游署名保留在 [NOTICE](../../NOTICE) 与 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)。Nanodot 与 Meta 及其 Muse 商标无关。

## 上游历史与署名

以下动态、论文引用、维护者与贡献者卡片、致谢来自 nanoMuse。原有日期、名字与链接保持，指向上游项目，不代表 Nanodot 的发布或维护者。

<details>
<summary>nanoMuse</summary>

## 🗞️ 动态

- `2026-10-09` 🚀 版本 [1.0.0 Keel](https://github.com/nano-muse/nanoMuse/releases/tag/v1.0.0) 发布，手机、电脑和浏览器都有；装在上一版之上，数据保留，每一处改动都写在发布说明里。
- `2026-10-08` 🤗 我们的论文登上 [Hugging Face Daily Papers](https://huggingface.co/papers/2610.08699)，在 10 月 8 日的榜单上排第三；欢迎到论文页提问和留言。
- `2026-10-07` 📄 我们的论文已发布在 [arXiv](https://arxiv.org/abs/2610.08699)：《nanoMuse: An Open-Source Personal Agent for Every Device You Own》，作者 Guangyi Liu、Yong Liu、Jiangning Zhang（浙江大学）；引用格式见本页末尾。
- `2026-09-25` 🎉 nanoMuse 以 GPL-3.0-or-later 发布在 GitHub：一个基于 OpenMinis 1.13 的 Android 应用；中继、电脑端和 iPhone 版在随后几天陆续到来。

全部版本：[releases](https://github.com/nano-muse/nanoMuse/releases)。

## 维护者

<table>
  <tr>
    <td align="center"><a href="https://github.com/lgy0404"><img src="https://avatars.githubusercontent.com/u/63797388?v=4&s=80" width="80" height="80" alt="Guangyi Liu"><br><strong>Guangyi Liu</strong></a><br><a href="mailto:guangyiliu@zju.edu.cn"><img src="https://img.shields.io/badge/e--mail-guangyiliu%40zju.edu.cn-0a66e4" alt="guangyiliu@zju.edu.cn"></a></td>
  </tr>
</table>

## 社区贡献者

每一位有改动合并进 `main` 的人，取自 GitHub 的贡献者列表。

<!-- contributors:start -->
<p>
<a href="https://github.com/wsvn53"><img src="https://avatars.githubusercontent.com/u/912204?v=4&s=48" width="48" height="48" alt="wsvn53"></a>
<a href="https://github.com/aliabbas6622"><img src="https://avatars.githubusercontent.com/u/193962983?v=4&s=48" width="48" height="48" alt="aliabbas6622"></a>
<a href="https://github.com/PovedaAqui"><img src="https://avatars.githubusercontent.com/u/9494679?v=4&s=48" width="48" height="48" alt="PovedaAqui"></a>
<a href="https://github.com/jiangkaiqi2005"><img src="https://avatars.githubusercontent.com/u/184961141?v=4&s=48" width="48" height="48" alt="jiangkaiqi2005"></a>
</p>
<!-- contributors:end -->

## ⭐️ 引用

如果 nanoMuse 对你有帮助，欢迎引用我们的论文。

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

## 致谢

nanoMuse 站在别人的工作之上；条款见 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)。

- [OpenMinis](https://github.com/OpenMinis/OpenMinis)：手机 App 所基于的端侧智能体，沙箱来自 [proot](https://github.com/nano-muse/proot) 和 [Alpine Linux](https://alpinelinux.org/)。
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)：桌面 App 作为插件运行在它上面。
- [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop)（字节跳动）：桌面端「手」的操作器移植自它，舞台上的标记参考它的 ScreenMarker。
- [MobileGym](https://github.com/Purewhiter/mobilegym)、[MemGUI-Bench](https://github.com/lgy0404/MemGUI-Bench)、[PhoneHarness](https://github.com/PhoneHarness/PhoneHarness)、[CopilotKit/OpenMuse](https://github.com/CopilotKit/OpenMuse)、[Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)、[ClawGUI](https://github.com/ZJU-REAL/ClawGUI)：手机操作器、轨迹和产品思路。

</details>

<p align="center"><img src="https://visitor-badge.laobi.icu/badge?page_id=Mola-maker.nanodot&amp;left_text=visitors" alt="Nanodot visitors"></p>
