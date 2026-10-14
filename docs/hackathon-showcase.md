# Tmall hackathon showcase

The [showcase source](../demo/hackathon/) presents Nanodot for the Tmall hackathon's “科技无碍” direction, with a [ModelScope Static project](https://modelscope.cn/studios/why2025/nanodot-crystal). Its central flow is selecting a confusing screen region, asking about it and following an instruction while keeping confirmation and Stop with the user.

## What the page demonstrates

| Surface | Evidence and scope |
|---|---|
| Browser interaction | A labelled local script illustrating circle, rectangle and free-form selection and a three-step Care example; no model request or real booking. |
| Android video | A 115.636-second continuous recording from the earlier API 35 x86_64 development candidate. Region image questions achieved 4/4 core facts; only 2/4 complete answers had no detected errors. |
| APK download | The separately signed [1.1.0 Crystal arm64 prerelease](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0), with checksum and public build receipt. The earlier video is not new runtime acceptance of that package. |
| Upstream website | A pinned, attributed nanoMuse website snapshot. Its remote demo and cloud claims belong to upstream; its backend is not included in this static space. |

The [Android verification record](android-verification.md#raster-region-live-2026-10-13) retains the original APK identities, question errors and Care limitations. This presentation does not establish a complete real-provider Care task, voice acceptance, physical arm64 operation or measured outcomes for a particular accessibility population. The cover is AI-generated promotional artwork, not a screenshot.

## Cover formats

The [portrait cover](../demo/hackathon/site/assets/hackathon-cover.png) is 1080 × 1920 and remains the homepage artwork. The [landscape cover](../demo/hackathon/site/assets/hackathon-cover-landscape.png) is 1920 × 1080 and provides a separate entry-upload option. Both are AI-generated promotional illustrations, not product screenshots.

On 2026-10-14, the Tmall form's help text requested a 1080 × 1920 portrait, but its actual file validator rejected that format and requested a 1920 × 1080 landscape. The landscape passed the dimension check; that alone does not establish a successful upload or entry. Check the live form when submitting and retain both variants.

## Preview and deployment

The canonical files are in `demo/hackathon/site/`. They can be served by any ordinary static file server:

```sh
python3 -m http.server 8080 --directory demo/hackathon/site
```

The site has no package installation or build step. To export for the ModelScope browser uploader:

```sh
python3 demo/hackathon/build-flat.py
```

Upload all files from `demo/hackathon/deploy-flat/` to the Static project's root and explicitly deploy that revision. The entrypoint remains `index.html`; the exporter replaces nested paths with flat filenames, validates references and emits `deployment-map.json` with original/exported hashes. Binary media remain byte-identical. Keep this generated directory out of Git.

After deployment, open the actual hosted page and verify mobile width, selection controls, video playback, upstream navigation and the signed APK link. Record the Git commit and deployed file hashes. A created project or a successful upload alone does not prove the hosted revision works.

## GitHub and cloud collaboration

GitHub holds the canonical source and review history. Contributions go through a DCO-signed pull request, focused checks and review. After a source change, exporting, uploading and activating the reviewed ModelScope revision are manual operations. No API token, automatic GitHub-to-ModelScope synchronization or model endpoint is configured by this page.

ModelScope hosts static files here. Android inference still uses the provider that the user configures; selected content may be sent to that provider. The inherited desktop runtime and Hub implementations provide architecture for local PC work and device coordination, but a completed AI PC/local-model/cloud/device round trip requires separate configuration and recorded acceptance. See [desktop](desktop.md), [Hub](hub.md) and [own-key setup](own-key.md).

Do not put provider secrets, signing files, personal application fields or private device recordings in the public site. Tmall identity fields and final competition submission remain separate from publishing code or a webpage.

## Attribution and sources

The [upstream record](../demo/hackathon/site/UPSTREAM.md) pins the actual website repository and distinguishes imported content from Nanodot. The source manifest records original and vendored hashes; retain it, the site licence and the repository's [NOTICE](../NOTICE) and [third-party notices](../THIRD_PARTY_NOTICES.md).

The [official event page](https://pages.tmall.com/wow/a/act/tmall/dailygroup/24732/25590/wupr?wh_pid=daily-597100#submit) and its [activity terms](https://terms.alicdn.com/legal-agreement/terms/c_end_product_protocol/20260824142803252/20260824142803252.html) control entry requirements. ModelScope's [official Static deployment guidance](https://github.com/modelscope/modelscope-skills/blob/main/skills/ms-studio-deploy/SKILL.md) explains the hosting format. A published project page does not assert eligibility, consent to terms or successful entry.
