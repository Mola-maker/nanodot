---
layout: home
title: Nanodot docs
hero:
  name: Nanodot
  text: Dot, an open-source personal agent.
  tagline: Bring your own model. Use conversation, tools, memory and scheduled work on your devices. Nanodot is a source preview based on nanoMuse and OpenMinis.
  actions:
    - theme: brand
      text: Build Android
      link: /build
    - theme: alt
      text: Quick start
      link: https://github.com/Mola-maker/nanodot#quick-start
    - theme: alt
      text: GitHub source
      link: https://github.com/Mola-maker/nanodot
---

## Start with a small task

Install the Python runtime from this repository, configure your model, then try a text
conversation or the local Web console. The [README quick start](https://github.com/Mola-maker/nanodot#quick-start)
walks through installation. You can also [build Android](build.md) for Android 8+ on ARM64.

For example, ask Dot to create a travel checklist and save it as a file. Read
[configuration](configuration.md), [permissions](sentinel.md) and [privacy](privacy.md)
as you add tools. To help the project, [report a reproducible result](https://github.com/Mola-maker/nanodot/issues/new/choose)
or read the [contribution guide](../CONTRIBUTING.md).

## Current scope

P0 keeps version **1.0.1** / Android versionCode **44**. Dot uses offline static faces;
existing names, SOUL.md, custom avatars and animations, models and preferences remain
preserved. The theme follows the system; continuous voice and automatic updates default
to off. New floating-ball and care-mode interaction belong to later phases.

## Build and downloads

Use the [build guide](build.md) for the fixed toolchain and `scripts/build-nanodot-apk.sh debug|release`. Release requires a dedicated signature with no debug-key fallback;
the wrapper never publishes. This page does not claim that an installable Nanodot release
exists or that real-device installation and signed upgrades have passed.

[Nanodot releases](https://github.com/Mola-maker/nanodot/releases) is the destination for
future published packages. Upstream nanoMuse APKs, desktop installers, demos and TestFlight
builds are separate products and are not Nanodot downloads.

## Inherited documentation and services

Most platform and runtime pages retain nanoMuse's implementation guidance and historical
references. Read them alongside [Nanodot's P0 build boundaries](build.md#other-platforms-and-acceptance).
Cross-platform configuration is synchronized; Xcode and native Windows/macOS packaging
still require their own platform validation.

**nanoMuse Cloud** remains the upstream service, with its real name, endpoints and policies.
Nanodot does not claim to operate it, promise free allowances or inherit nonprofit status.
Use your own provider or explicitly configure the existing integration.

## Upstream paper

The citation below belongs to nanoMuse and is retained as attribution.

## Citation

The report *nanoMuse: An Open-Source Personal Agent for Every Device You Own* is on arXiv as [2610.08699](https://arxiv.org/abs/2610.08699): what a personal agent is, how Muse is built, and how nanoMuse answers it in the open. If you find nanoMuse useful, please cite the paper.

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
