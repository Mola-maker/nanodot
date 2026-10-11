# Nanodot 安卓悬浮交互与关怀设计

2026-10-10 · 用户已采用设计 · 基于本地 nanoMuse 与已完成的 Nanodot P0

## 交付顺序与当前事实

安卓先行：完成浮球、前台对话、屏幕理解、圈选和逐步关怀，再同步桌面界面。Windows、macOS、iOS 的 P0 构建修复与产物验证继续进行。本文件描述后续实现，不代表这些功能已在当前 APK 中存在。

现有代码已具备 Compose/Material 3、对话与模型连接、图片附件、ASR/TTS、无障碍节点和 API30+截图。P0 已加入品牌、Dot 默认头像、主题和浮球默认偏好，但尚未创建默认浮球窗口，尚未实现多击、圈选或只读关怀控制器。

继续使用现有 Gradle Kotlin DSL 与官方推荐的分层方式：UI 呈现状态，控制器处理会话，Repository 管理屏幕与模型数据。新 Kotlin 文件进入 `io.github.nanomuse.dot`；外部应用身份继续为 `io.github.nanodot.app`。已有会话、模型密钥、自定义头像和主题使用原来的存储，不另建一套配置。

## 统一视觉与布局

视觉方向：安静、清晰的原生工具界面。Dot 是唯一人格视觉锚点，绿色用于普通互动，紫色只用于关怀。以实际任务、对话和下一步操作为中心，不堆叠装饰卡片。沿用 P0 离线 Dot 头像，拒绝把网络视频加载成功作为首次启动条件。

| Token | 浅色 | 深色 |
|---|---|---|
| background / surface | `#F4F7F8` / `#FFFFFF` | `#10191D` / `#182329` |
| text | `#16252C` | `#E5EDF0` |
| primary / onPrimary | `#136F63` / `#FFFFFF` | `#7DD6C5` / `#10191D` |
| care | `#7057A3` | `#C7B4F7` |
| outline | `#687980` | `#8EABB5` |

Android 使用系统字体，正文16sp、关怀说明18sp，间距使用4/8/12/16/24/32/48dp，触控区域至少48dp。按钮、输入框、菜单、卡片、弹窗、底部面板遵循 Material 3；每项都设计 disabled、pressed、loading、success、error 和 retry，状态不能只用颜色表达。

手机采用底部导航与单列对话；平板/宽窗口采用导航栏和会话列表、正文双列，实际宽度与字体缩放决定布局。主导航为对话、任务、设备、设置；现有 Feed/Ideas/Goals/Library 的功能保留，通过任务与资料入口映射，不在改导航时丢失能力。逐页验证横屏、分屏和100/150/200%字体，键盘出现后保持发送/停止按钮可达。

响应动画使用短促按压、状态过渡和聚焦框过渡；关怀进入时 Dot 外圈变紫并展开“我会一步步引导你”。系统减弱动画时提供静态状态，避免循环闪烁。TalkBack 有明确标签、状态公告与“查看屏幕 / 开始关怀”等等价操作，不依赖用户一定能完成双击/三击。

设计参照：[Anthropic Frontend Design](https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md)、[UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill)；具体选择以本项目的 Material 3 与上述 tokens 为准，不机械套用网页模板。

## 浮球生命周期与交互

浮球由已连接的无障碍服务持有，使用 `TYPE_ACCESSIBILITY_OVERLAY`。用户在系统设置启用无障碍后，默认显示48dp触控球；用户关闭后尊重保存值。服务断连、销毁、锁屏或用户结束交互时撤销相关窗口、录音与未完成请求；重连只恢复入口。默认显示不触发截图上传，也不打开麦克风。[Android overlay 类型](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY)

| 输入 | 最终动作 | 即时反馈 |
|---|---|---|
| 单击 | 展开前台 Compose 对话面板，同一明确会话的文字/语音交互 | 球按压反馈；面板显示输入与发送/停止 |
| 双击 | 手势确定后、打开 Nanodot 面板/Activity 前捕获宿主当前整个可见屏幕，附到本次会话上下文；后续文字/语音可继续询问 | 状态提示“正在查看屏幕”，捕获后显示可撤销预览 |
| 同球连续三击 | 进入关怀，读取宿主场景、询问目标并生成当前一步 | 紫色关怀过渡与目标输入 |
| 从球开始拖动，开放路径 | 移动球；结束后贴边，保存位置 | 拖动变色与轨迹预览 |
| 从球开始拖动，闭合路径 | 圈选区域，球回到原位；展示选区预览与特定任务输入 | 闭合后绿色区域边框，支持重选/取消 |

多击通过纯状态机裁决，使用系统 ViewConfiguration 时间/触控阈值：待确认期间不发出单击/双击业务动作，第三击取消此前候选；拖动、第二指、取消、旋转清除 pending。按下反馈立即显示，网络调用不参与裁决。

闭合路径初始规则沿用 Ultra 方案：长度至少160dp、实际填充面积至少48×48dp、首尾距离不超过 `max(24dp, 对角线×0.15)`；仅接受从球发起的路径，自交使用明确的 even-odd 填充，以实际填充面积拒绝往返细线等退化轨迹。阈值通过设备测试调整。全屏轨迹/聚焦层不可触摸，普通页面滑动不被截获；球和提示卡仅占自己的触控范围。

对话面板采用可管理生命周期的 ComposeView，支持 IME 输入、滚动历史、发送/取消、麦克风状态与收起；返回先关闭键盘，再收起面板回球。关怀中返回/收起进入 Paused，撤框并停止语音但保留任务；恢复时重新识别，不沿用旧目标。收起/结束会话不修改浮球偏好；用户关闭设置持久隐藏入口，服务断连/锁屏临时撤窗，恢复后按偏好显示。打开主应用与浮层绑定同一 sessionId；晚到的截图/回复不能注入后来切换的会话。Activity 对话可用于中间验证，但不能把这个中间状态称为浮层交付完成。

## 屏幕感知与圈选

每次采样生成不可变 `ScreenSnapshot`：请求 generation、sceneVersion、时间、宿主 package、window/display、旋转、bounds/insets/IME、节点 DTO、图片及坐标变换。只复制所需节点信息，不长期持有 AccessibilityNodeInfo，不提供点击或输入方法给关怀层。

API26–29 先支持节点文本降级；API30–33 使用显示截图；API34+关怀优先使用宿主窗口截图，双击全屏仍使用显示范围。旧版本如需像素识别，另加明确的 MediaProjection 授权流程。截图受保护、限速或权限失效时解释原因并提供可用降级，不把旧图冒充当前屏幕。[Android 截图 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))

采集协调器串行管理新旧调用，截图前隐藏自身球/面板并等待绘制；超时、取消与晚到回调都释放图片资源。宿主页面/窗口/旋转变化立即撤销关怀旧目标，关怀结果只在 generation 与宿主场景版本匹配时接纳；过滤自身球/面板事件，宿主 IME/insets 真正改变时更新坐标与场景版本。聊天中已确认的截图是带采集时间的历史附件，绑定指定 sessionId/request generation，打开面板后仍可回答该截图；不会因为 Nanodot 自身窗口出现而失效，也不会自动变成新屏幕。

圈选把屏幕坐标、截图坐标与模型缩放坐标集中转换。输出同一快照的选中节点及裁剪图片，选区之外遮罩后才上传。密码节点不作为文字上下文发送。识别优先使用可访问节点与已确认支持视觉的模型；Canvas/WebView 无节点时报告真实能力，OCR 若另引入依赖需独立核验中文效果、包体与许可证。

## 关怀：每次只指导一步

`CarePlanner` 使用已有 provider 的独立只读调用，传入目标、当前快照和已验证的步骤；不经过普通 agent 执行循环，不提供 tools，不调用 Hands.run、dispatchGesture、setText 或粘贴操作器。

状态为 `Observing → Clarifying / Guiding → AwaitingUser → Verifying → Completed / Recovering`，任意时刻可暂停、取消或重新识别。模型输出只有受限 proposal：快照引用、目标节点/受校验区域、操作意图、说明、预期页面变化与置信度；不接受任意脚本、裸坐标或旧节点引用。

显示当前一个目标的视觉框与一张短说明卡。卡中保留复制、重复说明、暂停、重新识别、结束。焦点框触摸穿透，不抢 TalkBack 焦点。目标移动或页面变化先清除框，再重新采样。

用户操作后，事件只作为线索，必须结合后续页面可观察条件验证。误点、广告刷新、无关滚动和固定等待不能推进；无法验证时说明不确定并要求用户确认。已有自动操作/远控运行时暂停关怀，避免把它产生的事件当作用户完成步骤。

淘宝示例按实际页面定位，不写死文字和坐标：

| 当前一步 | 用户执行 | 推进证据 |
|---|---|---|
| 找到外卖入口 | 用户点击框选入口 | 出现符合目标的外卖页面 |
| 找到搜索框 | 用户点击输入框 | 正确输入框获得输入状态 |
| 显示可复制“啤酒” | 用户复制并粘贴 | 目标框内容与查询一致；无法读取时明确确认 |
| 指向搜索按钮 | 用户点击搜索 | 查询与结果页一致 |

持续文字/语音可以修改目标或询问当前一步。任务示例先验收到搜索结果；购买、支付或其他提交仍由用户操作。

## 语音与真实 API

复用现有 SpeechRecognitionManager、ASR/TTS adapters 与朗读播放，新增单一 owner lease，防止主界面与浮层同时录音。录音需要用户主动开始与可见状态；录音前暂停朗读。默认 continuousVoice=false，主动会话可选择连续输入，停止/锁屏/失权清理。

录音权限由可见 Activity 请求；若返回宿主后继续录音，先在可见 Activity 启动声明完整的 microphone 前台服务，带可见停止通知。无障碍浮层不能被当作所有 while-in-use 限制的豁免。[Android microphone 服务](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)、[后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

新默认服务目标为 `https://molamaker.si`，具体 API 路径、协议、auth 与模型 IDs 待真实服务确认。完成 DNS/TLS/真实 API 验证后启用该默认服务并生成交付 APK，不能先把可用连接切到已知不可达服务。只调整全新安装或尚无账号/Cloud provider 的初始化默认值；存在旧账号、token 或 provider 但没有保存 baseURL 时，先补记旧服务 origin，不能把缺少 URL 键误判为新安装并把旧凭据发到新域名。不覆盖已存第三方服务或密钥，旧 nanoMuse Cloud 仍显示它的实际身份。服务端高权限密钥不编进 APK，不打印测试凭据。

上游 relay 当前实现 Chat Completions 与 SSE，视觉取决于上游支持；未实现 `/v1/audio/transcriptions`、`/v1/audio/speech` 或 `/v1/responses` 路由。自建语音需补音频路由或配置单独的真实 ASR/TTS 提供方，不能通过替换域名假定音频可用。

真实验收分开记录：TLS/health、登录/鉴权、模型列表、文字流式回复、视觉图像理解、取消/401/429/断网，ASR 与 TTS 各自独立验证。MockWebServer 用于错误与编码回归，不能被报告成真实 API 成功。

## molamaker.si 网站与 Cloudflare

参考 [上游主页源码](https://github.com/nano-muse/nano-muse.github.io) 的静态结构，制作 Nanodot 首页、下载、文档、隐私与自带密钥说明，使用相同 Dot 与中英/明暗主题。只展示已交付能力和可核验下载，演示未实现能力时明确标记设计预览。

当前 Cloudflare 控制台仅有邮件 DNS，没有根域/www 的网站/API记录，也未绑定 Nanodot Worker。域名不是已经可用的模型 API，系统代理的合成 DNS 地址不能用作 VPS 源站。

推荐沿用已有 Python relay 在用户服务器运行，Cloudflare 代理域名并承载/分发静态网页；现有 FastAPI/SQLite/auth/hub 不能直接原样放进 Workers。若用户选择全 Cloudflare 后端，另写网关/存储适配方案，不为此购买套餐或假定存在源站。尚需用户说明现有服务器/API 配置位置或允许部署的服务资源；客户端纯逻辑与 UI 工作可独立推进。

## 实施与验收边界

补充已确认决策：用户选择部署 Cloudflare Workers AI 网关与网页，限定免费额度。实施计划使用 `https://molamaker.si/v1` 的独立 OpenAI 兼容模型入口，不冒充现有 nanoMuse Cloud 的账号/Hub 服务。文字、视觉、ASR 分别采用经官方文档核查的免费候选；中文云 TTS 必须真实验证后才声明支持。保留旧 Cloud 的账户、origin 与凭据绑定，不跨域迁移旧 token。此补充取代上文尚待用户确认的服务器/模型来源问题；具体代码与部署步骤见实施计划。

| 阶段 | 交付 | 真实验证 |
|---|---|---|
| 安卓交互基础 | 服务浮球、手势 reducer、默认/关闭/重连、菜单入口 | 纯 JVM 多击/拖动边界 + 虚拟机 WindowManager、权限与撤销 |
| 前台对话与屏幕理解 | 浮层、会话 adapter、采样 DTO/转换、双击、语音 owner | 外部 fixture 独特标记，确认宿主来源；IME、返回、旋转；真实文字/视觉 API |
| 圈选 | 路径、裁剪遮罩、节点关联与区域对话 | 几何/屏幕密度/旋转，发送字节不含遮罩外图案；真实区域回答 |
| 一步到多步关怀 | planner/controller/verifier/focus，演示应用与第三方任务 | 无动作不推进，错页/误点恢复，用户完成条件才下一步；记录版本化步骤证据 |
| 全界面与网站 | 导航/组件系统、桌面原生适配、首页与真实服务默认值 | 字体/横屏/TalkBack、各平台构建、真实 TLS/API/网页检查 |

按用户选择优先采用虚拟手机。软件 TCG 与 ARM64 翻译可验证安装、UI 和合成窗口；无法据此宣称真实 arm64 原生工具、OEM 后台行为、第三方淘宝完整任务、麦克风或设备性能通过。每项结果分别报告通过、失败与尚未验证，不把构建成功等同于交互验收。

关键验收：单/双/三击不串触，服务失权撤销，取消晚到不注入，坐标与快照一致，圈选范围正确，关怀不自动点击且有推进证据，字体200%仍可操作，未配置/离线/拒绝权限都有明确恢复入口。
