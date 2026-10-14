# Nanodot 实施总览

2026-10-10；用户已采用安卓交互设计，并选择 Cloudflare Workers AI 免费额度。本轮完成的是后续实施计划；浮球/圈选/关怀与新API仍待实施，P0构建产物已经单独验证。

| 优先级 | 可独立验收的交付 | 实施计划 |
| --- | --- | --- |
| 1 | 安卓默认浮球与前台文字、全屏理解 | [Android Tasks1–4](2026-10-10-android-dot-care.md) |
| 并行支撑 | 免费、受鉴权Workers AI网关与molamaker.si网页 | [Gateway Tasks1–6](2026-10-10-workers-ai-site.md) |
| 2 | 安卓语音与圈选区域问答 | Android Tasks5–6 |
| 3 | 同球三击、用户执行的逐步关怀 | Android Tasks7–10 |
| 4 | 全应用导航/组件系统，先安卓后桌面/Web/iOS | [UI Tasks1–7](2026-10-10-cross-platform-ui.md) |

第一阶段可以在网关部署前完成手势、服务窗口、截图和mock协议测试；真实文字/视觉/语音验收必须在网关部署后，不能用mock代替。中文云TTS需真实验证；不可用时明确使用且验证系统中文朗读，同时保留云TTS失败记录。

推荐以subagent-driven逐任务实施并逐任务审查，安卓状态机、权限与截图协调有多处异步边界。独立网关/静态网站工作可并行，共享Kotlin coordinator的任务串行集成；root负责跨任务接口和最终真实验收。也可选择当前agent原生逐任务执行并在整分支末独立审查。

完成定义：安卓APK可安装，1/2/3击互斥、实际宿主截图/遮罩范围正确、前台对话/语音可用、关怀逐步证据成立；新TLS/文字SSE/视觉/ASR/已声明TTS实际可用且Free限额有效；各平台UI迁移不丢配置并有实际构建与UI证据。任何未验证或失败项都保留，不以Actions绿色概括全部功能完成。

P0基线：已推送`7ed8ce6e`；Android/iOS Actions源码基线`9736baf4`与当前相应树相同；四桌面平台Actions `38064149128`全部成功。2026-10-11安卓KVM复验完成空数据首启（1066ms）、中英文明暗主题、默认Dot和同版本重装配置保留，本次无新增ANR/崩溃；此前TCG软件模拟ANR的失败证据继续保留。iOS unsigned archive、Mac ad-hoc、Windows unsigned，以及各平台尚未验证的边界写在独立P0报告。

补充构建依赖：上游iSH/FFmpeg当前仅device slice，即使arm64也不能用于Simulator。UI计划Task5先补SDK隔离的Simulator natives，再执行真正的iOS UI tests；原unsigned device archive不冒称Simulator验证。
