# Nanodot 设计系统

Nanodot 使用各平台的原生控件和无障碍行为。默认视觉是随应用提供的离线 Dot 头像。系统字体、颜色和控件立即显示，不依赖字体下载、视频、远程头像或网络响应。用户已有的自定义头像和外观偏好继续生效。

## Token 来源 {#token-sources}

`config/brand.nanodot.json` 是唯一颜色来源；`config/ui.nanodot.json` 负责尺寸和时长。运行 `python scripts/gen-ui-tokens.py`；`--check` 在输出过期时以失败状态退出，不写文件。生成器在修改输出前检查两个输入、schema、整数尺寸和颜色对比度。无效颜色、负间距或不足的对比度不会改变任何已有输出。先暂存所有变化，再逐个原子替换文件；未变化文件保留时间戳。`scripts/rebrand.py` 共用 Android 颜色渲染器，因此重新生成品牌不会改变 Material 映射。

| Token | 数值 | 原生用途 |
| --- | --- | --- |
| Space | 4、8、12、16、24、32、48 | Android dp、iOS 点、指针客户端 CSS px |
| Body / care | 16 / 18 | Android sp；iOS 系统字体和动态字体；Web rem |
| Touch target | 48 | Android 最小 dp；iOS 保留原生无障碍最小尺寸；指针按钮也保留足够面积 |
| Page / press / focus | 180 / 100 / 200 ms | 动画时长，不表示必须播放动画 |

Kotlin 提供 `DotTokens.Space.S4` 到 `S48`、`Type.Body/Care`、`Motion.Page/Press/Focus/Breath/TrailLag/TrailFade/Snap` 和 `TouchTarget`。Swift 提供 `NanodotTokens.Space`、`Typography`、`Motion`（秒）、`touchTarget` 和 `Colors.Light/Dark`。Swift 调用方使用可缩放的系统文字，不能用固定字号绕过动态字体。桌面从 `harness/dsh-nanomuse/src/client/dot-tokens.ts` 导出 `dotTokens`。Web 和展示站 CSS 提供 `--dot-background`、`--dot-surface`、`--dot-text`、`--dot-primary`、`--dot-on-primary`、`--dot-care`、`--dot-outline`、`--dot-space-*`、`--dot-type-*`、`--dot-touch-target` 和 `--dot-motion-*`。CSS 默认跟随系统深色模式；`data-dot-theme="light|dark"` 选择保存的外观覆盖值。这些生成的接口供后续平台框架和组件任务使用。

桌面样式使用共享的明暗 tokens 提供默认蓝色色板。重新生成品牌只更新 token 导出，不改写 TypeScript 模板或用户保存的个人配色。次要和弱化文字使用 text 角色；outline 用于边框或禁用状态。现有 Chat、Tasks、Devices、Settings 导航、rem 字号和减少动态效果行为继续保留。

## 颜色角色 {#color-roles}

`DotColors.lightScheme/darkScheme` 为 Android 现有 `MinisTheme` 提供固定版本 Material 3 库支持的完整颜色角色；保存的主题选择仍决定使用哪个方案。深色主按钮不能使用浅色方案的 on-primary 前景。在两个已批准色板中，表面文字与主按钮文字的对比度均达到 4.5:1。生成器还检查 tertiary、secondary 和各容器的前景对比度。

| Material 角色 | 品牌 token |
| --- | --- |
| primary、secondary、error、surfaceTint | primary |
| onPrimary、onSecondary、onTertiary、onError | onPrimary |
| tertiary | care |
| background、primary/secondary/tertiary/error 容器、surfaceVariant、surfaceDim、surfaceContainerLowest | background |
| onBackground、onSurface、onSurfaceVariant、所有 on-container 角色、scrim | text |
| surface、surfaceBright、其他 surfaceContainer 角色 | surface |
| outline、outlineVariant | outline |
| inverseSurface / inverseOnSurface / inversePrimary | 相反色板的 surface / text / primary |

Error 有意复用已批准的高对比度 primary 配对，不添加新的色相。错误必须有本地化文字以及错误图标或状态播报。成功同样有文字和勾选图标。颜色不能是区分结果的唯一方式。Material 提供禁用透明度、涟漪和焦点行为，不用任意常量替换这些颜色。

## 状态约定 {#state-contract}

下列各组件族都遵守此约定；具体例外明确写出。对话框或导航目标在没有待处理操作时无需编造加载指示。产品文案（包括错误、重试和成功播报）来自平台本地化资源，并覆盖所有已有语言。渲染测试的英文标签属于测试数据，不是产品文案。

| 状态 | 必须具备的行为 |
| --- | --- |
| Enabled | 原生角色、无障碍名称、可操作目标及键盘操作 |
| Disabled | 原生禁用语义；不响应点击或值变化；保留标签和原因 |
| Pressed | 原生涟漪或高亮；目标不移动；确需自定义过渡时用 100 ms |
| Focused | 可见焦点、合理顺序和原生键盘行为；可选 200 ms 过渡 |
| Loading | 可见的本地化进度文字和原生进度语义；避免重复提交；停止或取消仍可操作 |
| Success | 本地化结果文字，加勾选图标或状态播报；保留有用的焦点 |
| Error | 就地或上下文展示本地化原因，加图标或播报；保留输入，避免突然移动焦点 |
| Empty | 用本地化文字解释空状态，提供相关操作；不用无法操作的占位控件 |
| Retry | 有名称的原生操作；保留数据，只在用户请求后重试，并显示加载和结果状态 |

## 原生组件 {#native-components}

| 组件族 | 原生实现及状态行为 |
| --- | --- |
| 主 / 次 / 图标按钮 | Android M3 `Button` / `OutlinedButton` / `IconButton`，其他平台用原生等价控件。Enabled、disabled、pressed 采用原生语义。Loading 防止重复激活，并保留停止操作。Success/error 使用相邻状态文字；empty 表示省略无关操作或解释为何不可用；retry 是另一个有名称的按钮。图标按钮有本地化无障碍名称，装饰图标没有名称。Android 目标至少 48 dp。 |
| 文本输入 | M3 `OutlinedTextField` 或原生文本框。区分可编辑、禁用、只读和焦点状态。加载时可保留编辑，除非操作确需锁定。成功有确认；错误有标签、说明文字和图标；空值保留标签或占位提示；重试保留原值。保留原生 IME 操作、选择、密码遮挡和自动填充。 |
| Dropdown | M3 暴露式下拉或原生选择器、菜单。Enabled/pressed 打开菜单，disabled 无法打开。选中值会被播报。Loading、success、error 描述选项获取状态；empty 解释为何没有选项；retry 只在用户请求后重新获取。Escape/返回关闭并将焦点交还触发控件。 |
| Switch / checkbox | M3 `Switch` / `Checkbox` 或原生开关。采用原生选中、未选中、适用时的混合和禁用语义，点击标签可以切换。Pressed 使用原生反馈。Loading 显示保存过程，防止重复切换；success/error 文字说明保存或失败的值；retry 保留选择值。Empty 只适用于外层设置组，并显示说明。 |
| Card / list row | M3 `Card` / `ListItem` 或原生分组、列表容器。仅可操作行能聚焦或点击，disabled/pressed 适用于其操作。Loading、success/error、empty、retry 属于行内容。纯信息卡不伪装成按钮。阅读顺序与视觉内容一致，每个次要操作有独立名称。 |
| Dialog / sheet | M3 `AlertDialog` / `ModalBottomSheet` 或原生模态。Enabled/disabled/pressed 适用于内部控件。Loading 保留取消或停止；success 可以在播报后关闭；error 保留内容并提供重试；empty 解释没有内容的原因。模态限制焦点范围，关闭后交还触发控件，返回或 Escape 遵循原生行为，破坏性操作明确命名。 |
| Navigation | M3 导航栏、rail 或原生标签、侧栏。200% 字体下每个目标仍保留可见的本地化文字标签；通过适配或换行安排布局，不隐藏标签。播报选中的目标；禁用目标说明可用条件；pressed/focus 使用原生行为。Loading、success/error、empty、retry 在目标页面内显示，不会禁用全部导航。返回和当前对话的发送、停止控件始终可达。 |

## 布局与动效 {#layout-and-motion}

Android 在小于 600 dp 时采用 compact，600–839 dp 为 medium，至少 840 dp 为 expanded。Compact 使用底部导航和单列；宽窗口使用 rail，可将会话与聊天并列。实际可用空间、大字体和键盘可能使面板折叠。Chat、Tasks、Devices、Settings 的含义不变；Tasks 保留 Feed、Ideas、Goals 及 Library 入口。已有模型、技能、连接器、记忆、文件、备份和终端仍可从 Settings 或原有上下文入口到达。

所有平台遵循减少动态效果设置。Android duration scale 为零时显示静态状态；iOS 读取 Reduce Motion；CSS 在 `prefers-reduced-motion` 下将生成时长设为零。自定义动画必须使用这些 token 和系统设置。避免循环闪烁，也不能在动画每一帧播报或移动焦点。禁用动效时使用原生静态焦点提示。

## 验证 {#verification}

运行 `python -m pytest tests/test_ui_tokens.py -q` 和 `python scripts/gen-ui-tokens.py --check`。仅在 debug 构建中的 `DotTokenGalleryFixtureActivity` 及设备测试 `DotTokenGalleryRenderTest` 使用真实主题渲染 M3 控件、禁用控件、错误和说明文字、空状态和重试、dialog、sheet、navigation，并覆盖两个色板以及 100%、150%、200% 字体。必须捕获并人工检查实际设备截图；截图测试通过证明渲染，不证明屏幕阅读器行为或原生 sandbox 操作成功。测试页面没有生产入口，release 构建中不存在；可复用的生产组件展示页属于后续组件任务。

平台验收矩阵还检查横屏、分屏、手机、平板、键盘和 IME、屏幕阅读器、保存的主题选择、减少动态效果，以及可达的发送、停止和返回。Android 优先实现；iOS 原生验证需要对应模拟器和设备工具链。[构建说明](build.md)介绍各平台独立检查。

## Android 页面组件 {#android-page-components}

`DotPageState` 描述真实的 `Loading`、`Ready`、`Empty(message)` 和 `Error(message, retryable)`。`DotStatusSurface` 提供文字、图形、礼貌播报和调用现有处理器的“重试”。`DotOperationStatus` 只给拥有取消句柄的操作显示“取消”。`DotComponentGallery` 是可复用的原生组件示例，覆盖启用、禁用、焦点/按下、加载、就绪、空、错误、重试、对话框和底部面板；示例选择不会调用提供商。

聊天输入区消费当前会话已有的 VM 和发送/停止回调。大字与较矮的键盘窗口同时出现时，收起顶部装饰，保留同一草稿，并让单行输入区内部滚动。真实键盘仍显示时的输入区边界与触摸“停止”，和收起键盘后的导航分别验证。

Cloud 表单、账户刷新和 Network 测试取消真实的 OkHttp 请求；保留输入，不制造取消错误。Cloud 共用请求构造和响应解析，保留原地址、认证及 HTTP 错误信息。ProfileSync 使用的阻塞 `profile`/`putProfile` 接口仍为同步边界；Cloud 离线刷新仍可能按原逻辑保留缓存账户。

页面状态来自其实际所有者。Library 和个人页活动加载真实本地数据。Goals、Ideas、Models、Hands、Computers 常直接展示已有同步存储、准备情况或配置，不伪造加载和失败。Feed 展示真实生成标记并保留专用会话入口；手动运行与定时任务共用会话队列，因此页面不提供会误停定时任务的单次取消。头像取消保留已完成候选、已保存外观和重试上下文，丢弃取消作业的迟到回调。

共用控件保留至少 48dp 的原生触摸边界及选中语义。正文使用生成的 tokens，大字让控件增高并滚动。Android 动画时长倍率为零时，加载和头像保持静态，自定义页面、按下和焦点动画关闭。新增 Nanodot 文案覆盖现有 17 个 Android 资源语言。原生 Activity instrumentation 验证真实生产页面和资源，不把现有无关编译阻塞的完整 instrumentation 套件描述为通过。真实 TalkBack 下一项手势产生的焦点事件是独立验收项。

共享状态文字使用当前色板的 onSurface 前景色，图标使用对应状态色，即使页面没有外层 Material Surface 也保持可读。组件画廊提供 Material Surface；原生像素对比度检查独立于语义标签是否存在。

发送/停止在聚焦和按住时保持 primary/onPrimary 配对，使用原生波纹及100/200毫秒聚焦/按压提示（减少动态效果时静态呈现）。目标完成和动态喜欢使用原生可勾选控件，状态来自现有存储；添加及帖子信息操作具有本地化名称。云端模式/用量标签通过原生标签角色及48dp触控区域呈现实际选中状态。

不可变的 Dot 前台快照包含连接与账号代际、模式、规范会话绑定和显示操作。显示操作只决定独立截屏、回复、语音与关怀所有者的展示优先级，不替代各自的生命周期。

共享动效时长：Page 180ms、Press 100ms、Focus 200ms、Breath 3200ms、TrailLag 80ms、TrailFade 240ms、Snap 240ms。TrailFade 是样本总寿命，TrailLag 不延长它。Snap 表达响应时长，Android 将它转换为阻尼比 0.9 的平台弹簧，刚度不作为跨平台语义导出。
