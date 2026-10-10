# Nanodot

<p align="center"><img src="assets/brand/nanodot-mark.svg" alt="Nanodot · Dot" width="112" height="112"></p>

[English](https://github.com/Mola-maker/nanodot/blob/main/README.md) | [简体中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md) | [繁體中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh-TW.md) | [Español](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_es.md) | [Français](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_fr.md) | [Bahasa Indonesia](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_id.md) | [日本語](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ja.md) | [한국어](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ko.md) | [Русский](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ru.md) | [Tiếng Việt](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_vi.md)

Nanodot is an open-source personal agent derived from [nanoMuse](https://github.com/nano-muse/nanoMuse), whose phone apps are based on [OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis). Its default assistant is **Dot**. This repository contains the Android and iOS apps, desktop shell, Web console and Python runtime.

## P0 scope

P0 establishes the Nanodot identity, shared brand configuration, offline Dot artwork and safe defaults. The new floating ball and care mode belong to later phases; their preference keys do not enable those features or grant accessibility permissions.

The theme follows the system. Dot has five bundled static faces; custom names, SOUL.md, avatars, animations, models and preferences remain yours. Continuous voice defaults to off. Automatic updates are disabled; check this repository’s releases manually.

The inherited version remains **1.0.1**, Android versionCode **44**. Build configuration is synchronized across platforms. Real-device installation and upgrade acceptance are pending; a Linux build cannot validate Xcode or native Windows/macOS packaging.

## Build and installation

Build Android locally from this checkout. Debug and release packages are separate; release requires a dedicated signing key and never falls back to a debug key. The build wrapper does not publish, upload or tag releases.

```sh
bash scripts/build-nanodot-apk.sh debug
# Dedicated local signing configuration is required for release.
bash scripts/build-nanodot-apk.sh release
```

Read the [build guide](docs/build.md), [brand contract](docs/brand.md) and [project documentation](docs/index.md). Nanodot packages, when published, belong on the [Nanodot releases page](https://github.com/Mola-maker/nanodot/releases). Upstream nanoMuse downloads and TestFlight builds are separate products.

Android uses `io.github.nanodot.app` and the external `nanodot://` scheme. Internal `com.openminis.app` and `io.github.nanomuse.*` packages, `minis://` routes and sandbox paths remain stable. The new application ID has separate private storage: importing nanoMuse data requires an explicit export/import, not automatic access to its files.

## Models and services

Use your own model provider or configure the existing relay integration. **nanoMuse Cloud** remains the upstream service’s name and endpoint; Nanodot does not claim to operate it or promise a free allowance. Its account, availability and data policies are the service provider’s.

## Contributing and licence

Use [Nanodot issues](https://github.com/Mola-maker/nanodot/issues) for this fork. Read [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md); commits require DCO sign-off. The licence is [GPL-3.0-or-later](LICENSE), with upstream attribution in [NOTICE](NOTICE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Nanodot is independent of Meta and its Muse trademark.

## Upstream history and credits

The following news, paper citation, maintainer and contributor cards, and acknowledgements are inherited nanoMuse records. Their original dates, names and links identify the upstream project, not Nanodot releases or maintainers.

<details>
<summary>nanoMuse</summary>

## 🗞️ News

- `2026-10-09` 🚀 Version [1.0.0 Keel](https://github.com/nano-muse/nanoMuse/releases/tag/v1.0.0) is out, for the phone, the desktop and the browser; it installs over the version before and keeps your data, and its release notes list every change.
- `2026-10-08` 🤗 Our paper is on [Hugging Face Daily Papers](https://huggingface.co/papers/2610.08699), ranked third on the list for October 8; questions and comments are welcome on the paper page.
- `2026-10-07` 📄 Our paper is available on [arXiv](https://arxiv.org/abs/2610.08699): *nanoMuse: An Open-Source Personal Agent for Every Device You Own*, by Guangyi Liu, Yong Liu and Jiangning Zhang (Zhejiang University); the citation is at the end of this page.
- `2026-09-25` 🎉 nanoMuse is released on GitHub under GPL-3.0-or-later: an Android app built on OpenMinis 1.13; the relay, the desktop app and the iPhone app came in the days after.

Every version: [releases](https://github.com/nano-muse/nanoMuse/releases).

## Maintainers

<table>
  <tr>
    <td align="center"><a href="https://github.com/lgy0404"><img src="https://avatars.githubusercontent.com/u/63797388?v=4&s=80" width="80" height="80" alt="Guangyi Liu"><br><strong>Guangyi Liu</strong></a><br><a href="mailto:guangyiliu@zju.edu.cn"><img src="https://img.shields.io/badge/e--mail-guangyiliu%40zju.edu.cn-0a66e4" alt="guangyiliu@zju.edu.cn"></a></td>
  </tr>
</table>

## Community Contributors

Everyone whose change has landed on `main`, from GitHub's contributors list.

<!-- contributors:start -->
<p>
<a href="https://github.com/wsvn53"><img src="https://avatars.githubusercontent.com/u/912204?v=4&s=48" width="48" height="48" alt="wsvn53"></a>
<a href="https://github.com/aliabbas6622"><img src="https://avatars.githubusercontent.com/u/193962983?v=4&s=48" width="48" height="48" alt="aliabbas6622"></a>
<a href="https://github.com/PovedaAqui"><img src="https://avatars.githubusercontent.com/u/9494679?v=4&s=48" width="48" height="48" alt="PovedaAqui"></a>
<a href="https://github.com/jiangkaiqi2005"><img src="https://avatars.githubusercontent.com/u/184961141?v=4&s=48" width="48" height="48" alt="jiangkaiqi2005"></a>
</p>
<!-- contributors:end -->

## ⭐️ Citation

If you find nanoMuse useful, please cite the paper.

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

## Acknowledgements

nanoMuse stands on other people's work; [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) has the terms.

- [OpenMinis](https://github.com/OpenMinis/OpenMinis): the on-device agent the phone app is built on, with [proot](https://github.com/nano-muse/proot) and [Alpine Linux](https://alpinelinux.org/) for the sandbox.
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): the agent harness the desktop app is a plugin of.
- [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop) (ByteDance): the desktop hands' operator is a port of theirs, and the stage's markers follow their ScreenMarker.
- [MobileGym](https://github.com/Purewhiter/mobilegym), [MemGUI-Bench](https://github.com/lgy0404/MemGUI-Bench), [PhoneHarness](https://github.com/PhoneHarness/PhoneHarness), [CopilotKit/OpenMuse](https://github.com/CopilotKit/OpenMuse), [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM), [ClawGUI](https://github.com/ZJU-REAL/ClawGUI): the phone operator, the traces and the product ideas.

</details>

<p align="center"><img src="https://visitor-badge.laobi.icu/badge?page_id=Mola-maker.nanodot&amp;left_text=visitors" alt="Nanodot visitors"></p>
