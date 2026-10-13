# Android 验收

此流程用于 Nanodot Android 验收与 APK 交付。仓库内的 `skills/verify-nanodot-android/SKILL.md` 通过 AGENTS.md 入口读取，没有全局安装。它适配 [pstack](https://github.com/cursor/plugins/tree/fe53d9e9093dbb9ae298563b1a9d702dd6e2ca08/pstack) 0.15.21 的部分原则，保留 MIT © 2026 Lauren Tan 许可；来源锁与许可证位于技能的 `references/` 目录。

构建通过、测试夹具通过和真实应用操作通过回答的是不同问题，应分别记录。原始目标仍是 Android 优先、透明蓝色玻璃、底部气泡聊天、自动避让和用户操作的关怀模式；此流程不代表其他平台完成，也不重启登录或网关部署。

## 准备与标识 {#prepare}

使用[统一构建配置](build.md)。在仓库根目录、已有工具链环境下执行命令。`NANODOT_ENV_FILE` 可指向现有本地环境文件；构建检查不需要加载服务商密钥。

```sh
bash scripts/build-nanodot-apk.sh doctor
bash scripts/build-nanodot-apk.sh check
bash scripts/build-nanodot-apk.sh debug-x86_64 --with-test
```

`check` 默认运行完整 JVM 测试；只有显式选择 `NANODOT_TEST_SCOPE=nanomuse` 时才缩小范围，报告必须注明。`debug-x86_64` 用于模拟器，`debug --with-test` 生成 arm64 主包/测试包。x86 通过或本地原生缓存均不能证明 arm64 运行行为，保留收据中的缓存来源限制。

将 `NANODOT_RECEIPT` 设为所选构建明确输出的 `receipt.json`，不要用通配符猜“最新文件”。从当前交接记录找到已有、归本任务所有的 QA 目录并设置 `NANODOT_QA_HOME`。其中应有 `install-candidate.py`、`run-native-case.py`、QA 模式守卫与实时桥接程序。这些本地辅助工具不打入 APK，技能也不下载它们。复用前检查源码；缺失时明确记录前提，不另造一套驱动。

```sh
: "${NANODOT_RECEIPT:?select the explicit paired candidate receipt}"
: "${NANODOT_QA_HOME:?locate the existing owned QA harness}"
python3 scripts/android/check_verification_evidence.py receipt "$NANODOT_RECEIPT"
```

检查器只读本地字节。它要求证书字段一致，真实签名验证仍以构建时的 `apksigner` 证据为准；它不证明设备此刻装了什么。

## 原生状态检查与侧栏 {#doctor}

由一个协调者独占 Gradle 与设备。当前本地驱动固定为 `emulator-5586` / `Nanodot_V2_QA`，不得默认操作 `emulator-5584` 或共享设备。首次操作和异常失败后，直接核对身份与安装包哈希：

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5586 emu avd name
"$ANDROID_HOME/platform-tools/adb" -s emulator-5586 shell getprop ro.product.cpu.abi
"$ANDROID_HOME/platform-tools/adb" -s emulator-5586 shell pm path io.github.nanodot.app
"$ANDROID_HOME/platform-tools/adb" -s emulator-5586 shell pm path io.github.nanodot.app.test
```

对返回的确切 APK 路径读取 `sha256sum` 并与收据比较。记录 API、语言、字体缩放、主题、旋转和动画设置。检查当前本地桥接服务健康状态与真实解码视频，不能只看缓存截图或已打开的标签。侧栏私密认证 URL 不进入日志和提交。不要通过清空应用数据、更换签名或改动另一台虚拟机来消除不匹配。

更换构建时使用已有守卫安装器：

```sh
python3 "$NANODOT_QA_HOME/install-candidate.py" "$NANODOT_RECEIPT"
python3 scripts/android/check_verification_evidence.py receipt "$NANODOT_RECEIPT" \
  --installed "$NANODOT_QA_HOME/installed-candidate.json"
```

第二条命令比较保存的安装记录，不会重新查询设备。将直接观察及其时间写进本轮记录。主包/测试包部分安装失败时，先修复这对包再测试。

## 操作与取证 {#drive}

阅读 `skills/verify-nanodot-android/references/features.md`，选择本次改动相关的真实用户入口和明确的测试方法。`NANODOT_CASE` 使用全新的小写字母、数字、连字符标签；驱动拒绝覆盖已有目录。

```sh
: "${NANODOT_CASE:?choose a fresh evidence label}"
python3 "$NANODOT_QA_HOME/run-native-case.py" "$NANODOT_CASE" \
  'io.github.nanomuse.dot.chat.DotForegroundLayoutTest#transcriptAndFullTouchControlRemainVisibleInShortImeAndMovedUpFramesAtAllFontScales' \
  --expected 1
python3 scripts/android/check_verification_evidence.py native \
  "$NANODOT_QA_HOME/logs/$NANODOT_CASE"
```

这是一个包含多种布局情形的 JUnit 方法，不能证明完整用户或服务商流程。驱动保存 `result.json`、`instrumentation.log` 和 `after.png`，需查看新截图。守卫将现有侧栏切为自动测试模式，锁定手动输入并保留真实视频；此时不要手动操作。原生测试限时 180 秒，超时会保持自动模式：停止明确知道的测试/应用工作、检查清理结果后，才能恢复手动模式。只杀掉 adb 客户端不会停止 instrumentation。

真实产品验收应从启动器打开已安装应用，必要时通过系统设置开启其无障碍服务，回到无敏感内容的宿主页面，再操作悬浮球。保留操作前、动作过程和结果的截图或视频、UI 状态与相关日志。核验文字密集/反差宿主上的玻璃可读性、气泡顺序、草稿与历史保留、菜单/键盘/返回顺序、触控尺寸、暂时上移避让及紧凑面板。本次受影响时覆盖 200% 字体和矮窗口/键盘场景，保持聊天可见。像素比较前固定宿主内容与动画相位；重设计无需逐像素复制旧界面。

真实模型与语音检查需要已授权的独立配置及应用内请求/结果证据。配置了 key、夹具回复、注入转写或录音动画都不是真实服务商/麦克风证明。前提缺失时，将该证据层级记为 `not_run` 或 `blocked`，继续独立可做的检查。

## 证据与修复循环 {#evidence}

使用 `skills/verify-nanodot-android/references/run-record.md`。状态（`not_run`、`pass`、`fail`、`blocked`）与证据层级（`source`、`artifact_integrity`、`unit`、`native_fixture`、`native_user_flow`、`real_provider`）分开记录。绑定收据、源码指纹、APK 哈希、设备条件、入口、动作、预期/实际、产物和清理结果。离线检查器不能把旧夹具输出绑定到今天的安装，也不能判断截图内容。

每轮修复包含一个假设、一项有依据的改动和一次针对性检查。同一失败连续两次未变，应先检查前提与状态再写补丁。不能弱化预期、删除失败证据或无理由反复跑完整矩阵。有便宜确定的回归路径就复用；不相关的编译错误不算行为 RED。产品缺陷与驱动漂移分别处理。清理只恢复本次改变的设置/资源并保留证据；用户要求的实时侧栏可继续显示。

| 循环 | 现有所有者与边界 | 所需证据 |
|---|---|---|
| 构建/安装 | 统一 wrapper、冻结源码指纹与成对收据；协调者串行构建/安装 | 字节/签名/包名/ABI；构建途中源码变化会拒绝产物 |
| 原生夹具 | 现有 runner/QA 守卫；显式方法、180 秒限时 | JUnit 结果与实际状态截图；超时后先清理设备再尝试 |
| 关怀 | `CareController`、`CarePlanner`、`ConfiguredCarePlanner`；单次服务商请求、采样 5 秒/规划 30 秒；无自动服务商重试或回退 | 当前快照、一个可检查的用户步骤、新观察验证结果；`UserConfirmed` 与 `Observable` 保持区分 |
| 聊天/服务商/语音 | 现有 canonical binding 与取消所有者 | Stop 撤销工作，晚到输出不能恢复已停止任务；真实服务商/音频与本地等待夹具分开测量 |
| CI/审查 | 现有任务/PR 所有者与明确审查范围 | 读取真实产物和可处理变化；不自动合并、部署或重启冻结服务 |

关怀提示词强化证据顺序，不增加工具、执行、schema 字段或无限循环。提出步骤、经过一段时间或模型自述均不代表完成。generation/revision 与 Stop 继续由代码约束，不能用提示词代替。

## 技能验证与交付 {#delivery}

纯 Python 检查器测试不需要 Gradle、设备或模型服务：

```sh
python3 -m unittest discover -s scripts/android -p test_verification_evidence.py -v
```

新的或修改后的验收路径，在协调者于真实目标走完启动/状态检查/一个映射功能/取证/清理并记录结果前，仍是草案。不为展示技能而重跑已经通过且不相关的测试。交付时明确最终 APK 的 ABI、签名、SHA-256 和剩余限制。debug APK 可以安装，不等于硬件与真实服务商检查全部完成。pstack 不赋予发布或外部发消息权限。

## 早期钴蓝版本快照 — 2026-10-13 {#cobalt-snapshot-2026-10-13}

该钴蓝版本已生成 x86_64 和 arm64-v8a 的配对 debug APK，并核验模拟器安装后的主包及测试包字节。两种构建共享源码指纹 `1cc6e334e6d357b48a555bfcd22a5ebf8c5d190c9acca15d312f8386121d6d3d`；arm64 尚未完成实体手机运行验证。这是开发产物，未达到完整发布验收。

- 限定范围 JVM 检查通过。修复200%字体下正文裁掉2像素后，原生消息布局18种窗口/字号/发送方组合和玻璃4种主题/字号组合通过；真实分屏及键盘边界测试通过。
- 一次真实 DeepSeek 纯文本调用返回HTTP200及6个SSE片段，用量为44输入、6输出token。主聊天与前台钴蓝气泡显示了同一会话回复“Nanodot 连接正常。”。面板通过实际点击水晶球打开；发送使用现有 `chat.uiPrompt` 进入共享生产VM及provider，因此不代表物理点击发送按钮已经验收。
- 一次性本机测试转发器仅在内存中读取既有私密凭据，原样转发真实SSE，将系统上下文替换为公开测试文本，限制128输出token并关闭工具和思考。此次不覆盖默认上下文、工具执行、麦克风或屏幕理解。APK和仓库不包含密钥。
- 在该快照中，关怀普通控件流程通过；200%字体返回、紧凑菜单 Expand 可见性及静态轮廓上边缘测试失败。已看到轮廓顶部裁切，部分测试滚动/窗口准入假设也需修正。图库测试后恢复服务还触发了独立的悬浮窗附着 `BadTokenException`。这些历史失败继续保留，以下定向复验更新其状态。

## 关怀定向复验（r13）— 2026-10-13 {#care-r13-2026-10-13}

以下原生夹具结果绑定已安装的 x86_64 开发主包与测试包，源码指纹为 `162a7d2406d0941d8cf54d57d182fb0963a861c6f0187bbb9871a04b24a5cee4`：

| APK | SHA-256 |
|---|---|
| 主包 | `9fb4b7f6155aef6b7e386703db24615138777c24df72eff69a7bd71ca4dba241` |
| 测试包 | `09e588a71d34b28b0fc445a61a0ee04c89957029a24ebd3c51c87f7b13b8f884` |

先前的布局、玻璃、分屏及真实 DeepSeek 证据仍绑定 `1cc6e334…`，没有在这对 APK 上重新运行。

- **静态轮廓：通过**（`reset-r13-static-bound`）。取消轮廓窗口对系统边距的二次调整后，顶部裁切得到修复。四条边均实际渲染，间隔 300ms 的两次截图中目标区域像素一致。夹具检查已附着 View 的屏幕边界和截图像素；不可触摸、不可聚焦的视觉层无需出现在无障碍窗口列表中。
- **200% 字体控件与单次返回：通过**（`reset-r13-font2-diagnostics`）。夹具保留完整 48dp 控件准入，在菜单内选中复制，观察宿主触摸结果后只发送一次返回。返回获接受后，后续观察才达到 `Paused`、移除卡片并清除轮廓。这支持夹具等待时序问题，但不能确定它是旧失败的唯一原因。本轮未修改产品返回逻辑。
- **紧凑控件：设备端完成，宿主执行器未完整完成**（`reset-r13-compact`）。按当前视口进行短距离滚动后，设备端 TestRunner 报告 1 项测试、0 失败、0 忽略。宿主执行器以 143 退出，未保存正常 stdout/result，因此不能记为整条执行器流程通过。
- **悬浮窗附着缺陷仍未解决。** 一次串行服务重连及随后的 UiAutomation 观察未出现新崩溃；这不代表先前的 `BadTokenException` 已修复，也不能排除重新附着的竞态。

最初的 Android 优先交付目标继续保持未完成。执行器限制、未解决的附着缺陷，以及尚未覆盖的设备和服务商场景，仍阻止整套助手完成验收。

## 关怀入口与消息留白（r14）— 2026-10-13 {#care-r14-2026-10-13}

消息内边距现按对话区域的实际可用空间决定。自然高度为 208dp 的关怀卡片在空间充足时保留文字上下各 8dp，仅当对话区域放不下缩放后的行高与内边距时压缩。r14 检查点时原生验证尚待运行，r15 结果见下文。

r14 真实服务商验证通过实际三击悬浮球、输入目标和点击发送发起。生产 SYSTEM 与 user JSON 原样保留，转发器仅将输出上限适配为 `max_tokens=1024` 并关闭思考。DeepSeek 返回 HTTP 200，使用 1,592 输入及 153 输出 token：**传输通过，产品流程失败**。关怀停留在 `Recovering`，未显示已返回的指引。

本次使用公开的两页测试宿主，不代表第三方应用已经支持。PageContains 步骤需要用户显式确认，不能记为自动成功。该检查点时，发送前等待真实键盘与布局稳定的修复尚待验证。


## 两条真实关怀指引已显示（r15）— 2026-10-13 {#care-r15-2026-10-13}

已安装的 r15 配对 APK 对应源码指纹 `9af766e7…`、主包 `ca33a2b7…`、测试包 `582125b4…`。670 项 JVM 测试全部通过。两项原生消息布局方法通过：自然高度 208dp 卡片保留文字上下各 8dp，原有字体缩放与受限布局的 18 种组合仍获覆盖。两条真实指引气泡也实测得到上下各 8dp 留白。

本轮在公开两页测试宿主中实际三击悬浮球、通过键盘输入目标并点击 Start guiding。键盘与布局稳定后，关怀显示第一条真实指引，并以蓝色轮廓标出 Next page。实际点击宿主按钮后，再显式点击 **I completed this step** 确认推进，关怀随后显示第二条真实指引及 Finish 按钮轮廓。两次 DeepSeek 请求均返回 HTTP 200：第 #16 次共用 1,690 token，第 #17 次共用 1,834 token。生产 SYSTEM/user 内容原样保留，输出上限为 1,024 token、关闭思考；每阶段只调用一次上游，无重试或拒绝。

最后实际点击 Stop 并到达 `Paused`；未点击 Finish、未到达 `Done`，也没有第三次请求。转发器清理已完成。这证明测试宿主上的两条计划实际显示及用户确认推进，不代表自动完成或第三方应用覆盖。证据保存在 `reset-r15-care-journey` 及复验运行 `0f619d9a-4fb3-4d14-83a0-4d872f5d03e2`。

独立的原生关怀输入／迟到响应回归现已**通过**（`reset-r15-care-input-events`，1 个方法，执行器退出码 0，用时 22.204 秒）。本次复验主包仍为 `ca33a2b7…`，测试包更新为 `52668f0f…`，源码指纹为 `ce093aa5…`。两次实际点击发送均位于可见键盘上方；随后两次采样的原生 IME 均不可见，快照 IME 边界均为空。两次提交恰好产生两次采样及两次被暂缓返回的本地规划调用；停止后放行迟到响应，会话仍保持 `Paused`。这是使用本地规划器的原生夹具回归，与上文两条真实 DeepSeek 指引分别记证。

前两次失败仍保留：`reset-r15-care-input` 在 `@Before` 服务连接夹具中超时；`reset-r15-care-input-rebind` 已通过连接前置，但独立测试窗口未接收服务的几何事件，发送按钮留在键盘下方，会话一直处于 `Idle`。夹具现等待系统确认撤销自身服务授权，独立测试也转发已有场景／几何事件，再准入真实发送点击。这两次失败均不能据此认定出现新的产品崩溃。真实麦克风 ASR、可听 TTS、历史 `BadTokenException` 及整套助手验收仍未完成。先前 r12/r13 结果继续绑定各自原始 APK。


## 统一钴蓝玻璃界面（r16）— 2026-10-13 {#cobalt-glass-ui-pending}

前台聊天和关怀外壳现使用透明钴蓝主体、内侧深蓝边缘与两条细高光，以及顶部连续的深蓝渐隐层。状态以小圆点和实时文字呈现；Stop 保留独立 48dp 图标及原有本地化说明，折叠和更多也保留 48dp 触控尺寸。草稿、控件和菜单使用局部钴蓝阅读底色与配对白字，原有消息颜色及按可用空间保留的上下 8dp 留白不变。配色只作用于前台组件；这些绘制边缘不会采样、模糊或折射其他应用的像素。

已安装的 x86_64 配对包对应源码指纹 `c9f984c6…`、主包 `e1645b9d…`、测试包 `5df3ccfe…`。670 项限定范围 JVM 测试全部通过。未修改的玻璃原生测试通过四种明暗主题／字号组合（`reset-r16-glass`），保留状态／文字 4.5:1 与宿主对比保留 0.55 的门槛。200% 深色主题截图的宿主对比保留值为 0.663。

新增 `DotForegroundAdaptiveLayoutTest` 的两个方法均通过（`reset-r16-adaptive`）：240/320/400dp 宽度与 100%/200% 字体，以及实际横屏下上述三种宽度、200% 字体。实测 Stop／折叠／更多保留完整且互不重叠的 48dp 触控边界，历史／输入区域可用，简短关怀消息完整；物理触摸分别触发对应回调／菜单。横屏仅由夹具 Activity 请求，结束后恢复原方向请求和字体配置。部分保存截图包含系统旋转过渡；尺寸与点击断言不代表每张截图都已处于静止动画帧。这组测试不证明所有语言、录音控件、平板或任意分屏均已适配。

恢复既有服务会话后，在公开两页宿主上实际三击水晶球，打开新的底部关怀卡片（`reset-r16-glass-live/cobalt-care`），实际显示局部钴蓝输入框、聊天气泡及独立图标控件。这次视觉检查未调用服务商。先前 r15 真实服务商结果继续绑定原 APK，不能代替本次像素验收。当前实现为透明着色与绘制光学边缘，尚未验证跨应用折射；历史服务重新附着问题和最初的完整助手目标仍未完成。
