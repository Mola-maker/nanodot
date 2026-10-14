---
license: gpl-3.0
sdk: static
---

# Nanodot · 每一步，都看得懂

天猫黑客松「科技无碍」方向展示主页。用户圈出屏幕里的疑问，再获得解释和分步操作提示，面向认知与数字可及性的日常使用情境。

- 项目源码：https://github.com/Mola-maker/nanodot
- 创空间：https://modelscope.cn/studios/why2025/nanodot-crystal
- 发布：https://github.com/Mola-maker/nanodot/releases

## 内容与边界

1. **浏览器本地交互示意**：圈形 / 矩形 / 自由轨迹、基于预设目标的解释，以及三步关怀流程。明确标记预设内容，无模型 API、无截图读取、无真实预约。
2. **真实 Android 连续录像**：115.636 秒公开 Canvas 场景，水晶球沿不同轨迹圈选、发送区域图像并对话、清除未发送预览。开发版录像不是签名 1.1.0 的新增设备验收。核心任务 4/4，完整回答 2/4 无发现错误，另外两条存在额外几何描述错误。
3. **nanoMuse 官方网页源码子页**：`upstream-demo/` 固定来源快照与变更记录见 `UPSTREAM.md`。上游远程演示、模型与云端服务仍由上游提供，不是此静态站点自建的服务。
4. **端云协同说明**：继承上游 Android / PC / 云端架构，区分源码中的能力与当前已完成的 Android 验收。未宣称 PC+云整链路通过、Intel NPU 加速、语音完整闭环或特定人群效果验证。

## 部署

这是已构建的纯静态网站，不需要 npm、Python 应用进程、GPU 或 API key。部署根目录必须包含 `index.html`，保持 `assets/` 和 `upstream-demo/` 的相对路径。魔搭选择 Static SDK 后上传此目录文件即可。所有静态资源使用相对路径，支持部署在带前缀的子路径。

本地检查可用任意静态文件服务器打开目录。主页面无需外部依赖；上游子页的 GitHub 星数、公开在线 demo 与外链依赖网络。

1.1.0 Crystal 签名预览版已发布；发布协调者已重新下载并核验 APK、校验文件与构建记录。`app.js` 的 `release.published` 已启用。发布地址：https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0 。发布标签对应提交 `66dd9cdc`。

## 归属与素材

Nanodot 基于 nanoMuse 和 OpenMinis，保留上游 GPL-3.0-or-later 归属，许可证见 `LICENSE`。上游官网品牌、文案、展示素材仍属于上游项目。`assets/crystal-orb-atlas.png` 来自 Nanodot 已有的内置 imagegen 品牌资源；连续录像和区域图片来自实际开发验收。参赛封面如已包含，是 AI 生成的宣传视觉，不是应用截图。无人物与人群效果的实测声明。
