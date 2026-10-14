---
layout: home
title: Nanodot docs
hero:
  name: Nanodot
  text: Dot, an open-source personal agent.
  tagline: Android preview 1.1.0 Crystal brings a floating crystal orb, foreground chat, region questions and Care guidance. Based on nanoMuse and OpenMinis.
  actions:
    - theme: brand
      text: Download Android preview
      link: https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0
    - theme: alt
      text: Release notes
      link: /releases/v1.1.0
    - theme: alt
      text: GitHub source
      link: https://github.com/Mola-maker/nanodot
---

## Android preview · 1.1.0 Crystal

This first Nanodot Android prerelease targets **Android 8.0+ / ARM64**, version **1.1.0**
(versionCode **45**). Download [`Nanodot-1.1.0-arm64.apk`](https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk)
and its [SHA-256 file](https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk.sha256)
from this repository. The APK uses a dedicated Nanodot release key; read the
[release notes](releases/v1.1.0.md) before replacing a development build with a different signature.

The crystal Dot opens foreground chat in a blue glass panel. Select part of the screen,
review the local preview and ask a question; models explicitly configured for images
receive the masked image, while other models use available selected text. Care shows a
target and an instruction, keeps the instruction in chat, and offers step confirmation and Stop.

The theme follows the system. Custom names, SOUL.md, avatars, animations, models and
preferences remain yours. Continuous voice and automatic updates default to off.

## Actual Android captures

<table>
  <tr><th>Nanodot home</th><th>Care conversation</th></tr>
  <tr><td><img src="./superpowers/assets/2026-10-14-main-care/home-welcome.png" alt="Nanodot welcome screen captured on the API 35 emulator" width="260"></td><td><img src="./superpowers/assets/2026-10-14-main-care/care-transcript.png" alt="Care instruction retained over a controlled test page" width="260"></td></tr>
</table>

These actual API 35 emulator captures come from earlier development candidates. The Care
example uses a controlled test page and a local planner. They are not a new runtime
acceptance run of the signed 1.1.0 APK. The [verification record](android-verification.md#main-care-trace-2026-10-14)
identifies the APKs and the scope of each run.

## Preview limits and source builds

A complete real-provider Care task ending in verified success, arbitrary third-party apps,
ARM64 hardware, older Android versions, Chinese microphone recognition and audible speech
still need broader acceptance. The [controlled image journey](android-verification.md#raster-region-live-2026-10-13)
records correct core results in 4/4 questions, but only 2/4 complete answers without detected
errors. Current iOS and desktop runtime acceptance remains separate.

Use the [build guide](build.md) for the fixed toolchain and
`scripts/build-nanodot-apk.sh debug|release`. Release builds require a dedicated signature
with no debug-key fallback; the wrapper never publishes. Nanodot has separate private
storage from nanoMuse, so data migration requires explicit export/import. Same-ID updates
also require a matching signature and a higher versionCode.

## Start with a small task

Install the Python runtime from this repository, configure your model, then try a text
conversation or the local Web console. The [README quick start](https://github.com/Mola-maker/nanodot#quick-start)
walks through installation. You can also [build Android](build.md) for Android 8+ on ARM64.

For example, ask Dot to create a travel checklist and save it as a file. Read
[configuration](configuration.md), [permissions](sentinel.md) and [privacy](privacy.md)
as you add tools. To help the project, [report a reproducible result](https://github.com/Mola-maker/nanodot/issues/new/choose)
or read the [contribution guide](../CONTRIBUTING.md).

## Inherited documentation and services

Most platform and runtime pages retain nanoMuse's implementation guidance and historical
references. Upstream nanoMuse APKs, desktop installers, demos and TestFlight builds are
separate products. Nanodot downloads belong on the [Nanodot releases page](https://github.com/Mola-maker/nanodot/releases).
Read platform-specific claims alongside the [build and acceptance notes](build.md#other-platforms-and-acceptance).

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
