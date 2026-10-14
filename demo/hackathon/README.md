# Nanodot Tmall hackathon showcase

A static project page for the Tmall hackathon's “科技无碍” direction. The canonical website is in [`site/`](site/). It combines a clearly labelled scripted browser interaction, an actual Android development recording, the verified 1.1.0 APK download and an attributed snapshot of the upstream nanoMuse website.

- [ModelScope project](https://modelscope.cn/studios/why2025/nanodot-crystal)
- [Android 1.1.0 release](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0)
- [English deployment and collaboration guide](../../docs/hackathon-showcase.md)
- [中文部署与协作说明](../../docs/zh/hackathon-showcase.md)
- [Site scope and media provenance](site/README.md), [upstream source record](site/UPSTREAM.md)

## Preview and export

From the repository root:

```sh
python3 -m http.server 8080 --directory demo/hackathon/site
```

Open the local server in your browser. The main interaction uses scripted local data; it does not call a model, control an Android device or make a real booking. The video is a separately identified earlier Android run.

For ModelScope's browser multi-file uploader:

```sh
python3 demo/hackathon/build-flat.py
```

Upload the contents of the ignored `demo/hackathon/deploy-flat/` directory to the Static space root. `index.html` remains the entrypoint; nested paths become filenames containing `--`. The exporter validates local references and records source/deployed hashes in `deployment-map.json`. Keep the canonical hierarchy in GitHub and do not commit a duplicate exported bundle.

The page is already built. It needs no application server, GPU, API key or build command on the hosting platform. GitHub is the source of truth; copying the reviewed export and activating a ModelScope deployment are explicit manual steps. No cross-service token or automatic synchronization workflow is configured here.

## 中文

这是天猫黑客松「科技无碍」方向的静态展示主页。`site/` 保留规范层级源码，`build-flat.py` 生成适合魔搭多文件上传的扁平部署包；生成目录已忽略。主页中的交互示意、此前开发版的 Android 连续录像、已发布 APK 与上游官网快照各自标明边界。

GitHub 保存权威源码，魔搭提供静态网页托管；修改经 PR 审查和验证后，需要手动重新导出、上传并部署。这里没有配置自动同步、推理服务、远程手机控制或平台密钥。实际云模型、设备 Hub 和 AI PC 端云链路仍需要独立配置与连续验收。

比赛身份、学校、联系方式及最终提交由参赛者填写；网页发布或 GitHub push 不代表比赛报名成功。
