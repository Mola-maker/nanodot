# 天猫黑客松展示主页

[展示页源码](../../demo/hackathon/)面向天猫黑客松「科技无碍」方向，并配置了[魔搭 Static 项目](https://modelscope.cn/studios/why2025/nanodot-crystal)。主线是圈出看不懂的屏幕区域、提问并跟随操作提示，确认与停止始终由用户掌握。

## 页面展示的内容 {#what-the-page-demonstrates}

| 页面部分 | 证据与范围 |
|---|---|
| 浏览器交互 | 已标明的本地预设脚本，演示圆形、矩形、自由轨迹圈选和三步 Care 示例；没有模型请求或真实预约。 |
| Android 视频 | 此前 API 35 x86_64 开发候选包的 115.636 秒连续录像。区域图像问答核心事实 4/4，完整回答仅 2/4 未发现错误。 |
| APK 下载 | 独立签名的 [1.1.0 Crystal arm64 预发布](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0)，含校验文件和公开构建回执；此前录像不是该发布包的新运行验收。 |
| 上游官网 | 固定版本且保留归属的 nanoMuse 官网快照；其中的远程演示与云服务声明属于上游，本静态空间没有部署其后端。 |

[Android 验收记录](android-verification.md#raster-region-live-2026-10-13)保留原 APK 身份、问答错误与 Care 边界。该展示不代表完整真实模型 Care 任务、语音验收、arm64 真机或特定无障碍人群效果已经验证。封面是 AI 生成的宣传视觉，不是应用截图。

## 封面尺寸 {#cover-formats}

[竖版封面](../../demo/hackathon/site/assets/hackathon-cover.png)为 1080 × 1920，继续用于主页；[横版封面](../../demo/hackathon/site/assets/hackathon-cover-landscape.png)为 1920 × 1080，另供报名上传选择。两者均为 AI 生成的宣传视觉，不是产品截图。

2026-10-14 实际操作中，天猫表单帮助文字要求 1080 × 1920 竖版，但文件校验拒绝该尺寸并提示需要 1920 × 1080 横版。横版通过了尺寸校验；这不代表文件已经上传或报名成功。最终提交时请以实时表单为准，同时保留两种尺寸。

## 预览与部署 {#preview-and-deployment}

规范源码位于 `demo/hackathon/site/`，可通过普通静态文件服务器预览：

```sh
python3 -m http.server 8080 --directory demo/hackathon/site
```

网站无需安装依赖或执行前端构建。为魔搭浏览器上传器导出：

```sh
python3 demo/hackathon/build-flat.py
```

将 `demo/hackathon/deploy-flat/` 中全部文件上传到 Static 项目根目录，再明确部署该版本。入口仍为 `index.html`；脚本将层级路径改为扁平文件名，验证引用，并输出含源码与导出文件哈希的 `deployment-map.json`。二进制媒体字节保持一致。生成目录不进入 Git。

部署后打开实际托管页面，检查手机宽度、圈选控件、视频播放、上游子页导航和签名 APK 链接，记录 Git 提交与部署文件哈希。创建项目或上传成功本身不能证明在线版本可用。

## GitHub 与云端协作 {#github-and-cloud-collaboration}

GitHub 保存规范源码和审查历史。贡献通过带 DCO 签署的 PR、对应检查和评审进入主分支。源码更新后，需要手动导出、上传并激活已审查的魔搭版本。本页面没有配置 API token、GitHub 到魔搭的自动同步或模型端点。

魔搭在这里托管静态文件。Android 推理仍调用用户配置的服务商，选中内容可能发送给该服务商。继承的桌面运行时与 Hub 提供 PC 本地工作及设备协作的架构，但 AI PC／本地模型／云／设备的完整往返需要独立配置与录制验收。参见[桌面端](desktop.md)、[Hub](hub.md)与[自己的 key](own-key.md)。

模型密钥、签名文件、个人报名信息和私人设备录像不进入公开站点。天猫身份字段及最终参赛提交与代码、网页发布是独立步骤。

## 归属与来源 {#attribution-and-sources}

[上游来源记录](../../demo/hackathon/site/UPSTREAM.md)固定实际官网仓库版本，区分导入内容与 Nanodot。来源清单记录原始与导入后哈希；请保留该清单、网站许可证，以及仓库的 [NOTICE](../../NOTICE) 和[第三方声明](../../THIRD_PARTY_NOTICES.md)。

参赛要求以[官方活动页](https://pages.tmall.com/wow/a/act/tmall/dailygroup/24732/25590/wupr?wh_pid=daily-597100#submit)与[活动须知](https://terms.alicdn.com/legal-agreement/terms/c_end_product_protocol/20260824142803252/20260824142803252.html)为准。魔搭的[官方 Static 部署说明](https://github.com/modelscope/modelscope-skills/blob/main/skills/ms-studio-deploy/SKILL.md)解释托管格式。项目主页发布不代表参赛资格、同意条款或报名成功。
