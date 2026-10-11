# Android Dot Interaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在安卓无障碍服务中交付默认浮球、同球单/双/三击、前台文字与语音对话、圈选和由用户执行的逐步关怀。

**Architecture:** 服务拥有小范围可触摸浮球/对话窗和不可触摸绘制层；纯 reducer 裁决手势，Repository 采样屏幕，独立 CarePlanner/Verifier 管理指导。主应用和浮层通过现有 ChatViewModelStore 共享明确的 sessionId；关怀绝不进入自动操作循环。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、Lifecycle/SavedState、Coroutines、现有 OkHttp/providers、JUnit、Compose instrumentation、Gradle Kotlin DSL。沿用 minSdk26、现有 JDK21/SDK/NDK。

**Spec:** `../specs/2026-10-10-android-dot-care-design.md`；网关和整个应用导航分别见另外两份计划。

## Global Constraints

- 安卓交互为首要目标；新文件放 `io.github.nanomuse.dot`，applicationId=`io.github.nanodot.app`，保留 Gradle namespace、sandbox/CLI/storage。
- 使用 `TYPE_ACCESSIBILITY_OVERLAY`；真实服务连接才显示。默认显示不截图、不上传、不录音；用户关闭值优先。
- 触控区域至少48dp；正文16sp、关怀18sp；间距4/8/12/16/24/32/48dp；使用已批准的 P0 明暗 tokens 与系统字体。
- 多击互斥，使用 ViewConfiguration；同一个球连续三击才关怀。continuousVoice=false；录音必须主动启动。
- 选区长度≥160dp，even-odd实际填充面积≥48×48dp，闭合距离≤max(24dp,对角线×0.15)。球回原位；开放路径移动贴边。
- API26–29节点降级；API30+显示截图；API34+关怀优先宿主窗口。旧版本像素通过单独 MediaProjection 授权入口提供。
- 关怀当前场景严格匹配，聊天截图作为带时间的历史附件。截图/语音晚到不得串会话；密码文字不上传，选区外遮罩后上传。
- 关怀无 tools、无自动点击/输入/粘贴；只在后续场景证据成立后推进。暂停撤框并停止语音；恢复重新识别。
- upstream hook 带 `// nanoMuse:`；新增 nm_ 文案覆盖所有现有 locale；行为同改英文/中文 docs 和 CHANGELOG；每任务 DCO commit，不做版本升级。
- Mock 测试与真实 API/设备结果分别记录；软件 TCG 的 ANR 不能以延长超时或关闭系统 ANR 检测变成“通过”。

## Review Focus

- 第二次按下后长按、第三击边界、第二指或旋转：不提前触发单/双击，不留下延迟任务（Task1）。
- 截图取消后回调/硬件缓冲晚到：释放资源，错误会话不能收到附件（Task3/4）。
- 自身面板事件和宿主 IME/分屏变化：前者不污染宿主，后者立即更新坐标并撤旧框（Task3/8）。
- 锁屏、服务失权、主界面竞争麦克风：窗口和录音都撤销，用户关闭偏好不被重写（Task2/5）。
- 广告刷新/误点/搜索结果与查询不符：停留当前步或恢复，不按 click 事件盲目推进（Task8/10）。

---

## 文件边界与运行方式

下文 `main/`、`test/`、`androidTest/` 分别指 `android/src/android/app/src/{main/java,test/java,androidTest/java}/io/github/nanomuse/dot/`。`res/` 指该 app 的 `src/main/res/`。所有新接口归上述包，纯 DTO 不持有 AccessibilityNodeInfo。命令在 `android/src/android` 运行；`UT(X)`=`./gradlew :app:testDebugUnitTest --tests 'io.github.nanomuse.dot.X' --console=plain --no-daemon`。仪器测试运行 `./gradlew :app:connectedDebugAndroidTest --console=plain --no-daemon`，在报告中保留设备/API/实际测试数量。

### Task1: 可测试的多击和拖动裁决

**Files:** Create `main/gesture/DotGestureReducer.kt`; Test `test/gesture/DotGestureReducerTest.kt`。

**Interfaces:** Produces `PointPx(x:Float,y:Float)`；`GestureLimits(doubleTapMs:Long,longPressMs:Long,touchSlopPx:Float,doubleTapSlopPx:Float)`；`GestureEvent.Down/Move/Up(point:PointPx,atMs:Long,pointerId:Int)`, `Tick(atMs:Long)`, `Cancel`；`DotIntent.Single/Double/Triple`, `Drag(points:List<PointPx>)`；`GestureResult(intents:List<DotIntent>,deadlineMs:Long?)`；`DotGestureReducer.accept(event:GestureEvent):GestureResult`。

- [ ] 写 `exclusiveTaps`、`secondDownSuspendsDeadline`、`dragAndCancelClearCandidates`：`single(0..120).tick(420)==[Single]`；两次Up在120/350、third在580→仅Triple；触控移动超过slop→仅Drag；第二指/Cancel/rotation→无旧回调。测试 limits=300ms/500ms/8px/100px；实际设备值由 ViewConfiguration 注入。
- [ ] 跑 `UT(gesture.DotGestureReducerTest)`，确认缺少 reducer 导致 FAIL。
- [ ] 实现上述接口，用 monotonic time；候选间隔到期才发业务动作；相邻tap间隔严格小于doubleTapMs才合并，等于阈值归新序列，与Tick在同一时间的处理一致；合格第二Down挂起上次到期，长按到系统longPress阈值清候选。按压反馈独立于业务动作。
- [ ] 重跑该 UT，全通过；增加第三击恰好阈值内/外、已过期后的新单击断言。
- [ ] DCO提交 `feat(android): arbitrate Dot orb gestures`。

### Task2: 服务浮球、设置及窗口生命周期

**Files:** Create `main/overlay/DotOverlayController.kt`, `DotOverlayOwner.kt`, `DotOrb.kt`, `DotOverlayPolicy.kt`, `main/DotSettings.kt`; Modify `com/openminis/app/accessibility/MinisAccessibilityService.kt` 的 connect/event/interrupt/destroy hooks、现有设置入口、`res/values*/nm_strings.xml`；Test `test/overlay/DotOverlayPolicyTest.kt`, `androidTest/overlay/DotOverlayLifecycleTest.kt`。

**Interfaces:** Consumes Task1 reducer、`DotPreferences.showOnAccessibilityConnected():Boolean`。Produces `DotOverlayController.connect()/disconnect()/setLocked(locked:Boolean)/setEnabled(enabled:Boolean)/onIntent(intent:DotIntent)`；`DotOverlayOwner` 实现 LifecycleOwner/SavedStateRegistryOwner；`OverlayKind.Orb/Panel/Trail/Focus`；`OverlayPolicy.windows(connected:Boolean,locked:Boolean,enabled:Boolean):Set<OverlayKind>`；`@Composable DotOrb(onIntent:(DotIntent)->Unit)`。业务 intent 委托到后续 coordinator，尚未可用的菜单项明确说明而非假执行。

- [ ] 写断言：`windows(true,false,true)=={Orb}`；关闭/失权/锁屏均空；锁屏恢复仍服从enabled；连续connect窗口数量=1；断连每个remove恰好一次；零截图/录音调用。
- [ ] UT与仪器测试先失败；记录 virtual phone OS/API，不以权限命令成功代替窗口显示。
- [ ] 创建48dp ComposeView orb；raw屏幕坐标喂 reducer。仅Orb/Panel可触摸，Trail/Focus设NOT_TOUCHABLE/NOT_FOCUSABLE；owner恢复/销毁与窗口同步。加入开关和TalkBack“打开对话/查看屏幕/开始关怀”等价菜单；锁屏/失权清pending并撤窗。位置归一化按有效display范围存现有appearance prefs。
- [ ] 重跑 UT、仪器测试；外部应用中滑动未受阻、服务重连/关闭/旋转撤窗通过后截图。
- [ ] 更新 docs/android.md、docs/zh/android.md、CHANGELOG 并 DCO提交 `feat(android): show Dot orb with accessibility`。

### Task3: 场景、截图与坐标 Repository

**Files:** Create `main/screen/ScreenSnapshot.kt`, `ScreenRepository.kt`, `CaptureCoordinator.kt`, `ScreenTransform.kt`, `NodeReader.kt`, `LegacyProjectionCapture.kt`；Modify accessibility hook 与 `AndroidManifest.xml` 的显式 projection 授权入口（若添加projection service，按系统类型声明）；Test `test/screen/{ScreenTransformTest,CaptureCoordinatorTest}.kt`, `androidTest/screen/ScreenRepositoryTest.kt`。

**Interfaces:** Produces `RectPx(left:Float,top:Float,right:Float,bottom:Float)`；`NodeDto(ref:String,bounds:RectPx,text:String?,description:String?,viewId:String?,role:String,focused:Boolean,clickable:Boolean,editable:Boolean,enabled:Boolean,password:Boolean)`；`SceneKey(packageName:String,windowId:Int,displayId:Int,version:Long)`；`ScreenSnapshot(id:String,generation:Long,capturedAtMs:Long,scene:SceneKey,bounds:RectPx,rotation:Int,insets:RectPx,imeBounds:RectPx?,nodes:List<NodeDto>,image:Bitmap?,transform:ScreenTransform)`；`CapturePurpose.Chat/Care/Selection`；`CaptureOutcome.Success(snapshot:ScreenSnapshot)/Unavailable(code:String)`；`suspend ScreenRepository.capture(purpose:CapturePurpose,generation:Long):CaptureOutcome`；`ScreenTransform.screenToImage(point:PointPx):PointPx`。Repository owns pixel buffers until handed off; receiver closes snapshot via `ScreenSnapshot.close():Unit`。

- [ ] 写 `rotationAndInsetsRoundTrip`：四rotation的角点来回误差≤1px；`lateCallbackReleased`：取消的hardwareBuffer/bitmap恰好close一次；`hostImeChangesScene`：Nanodot overlay事件version不变、宿主IME/insets变化version增加；password DTO text=null。
- [ ] UT先FAIL；instrumentation用独特宿主标记验证捕获来源，不只验证位图非空。
- [ ] 实现单个串行采集队列；截图前暂时隐藏自窗并等待实际绘制。新异步 callback adapter 正确释放晚到资源，不复用会遗留late结果的同步包装；API26–29节点fallback/显式projection；API30–33 display；API34+ care宿主window。保护屏/限速/timeout分别返回code，不回旧图。
- [ ] 重跑测试；覆盖锁屏、multiwindow、IME、display不同大小、protected fixture、取消后新request不串图。
- [ ] DCO提交 `feat(android): sample host scenes for Dot`。

### Task4: 真正的前台对话及双击屏幕附件

**Files:** Create `main/chat/DotChatAdapter.kt`, `DotChatPanel.kt`, `DotSessionBinding.kt`, `main/DotInteractionCoordinator.kt`；Modify Task2 controller 与既有主聊天进入hook（带upstream注释）；Test `test/chat/DotSessionBindingTest.kt`, `androidTest/chat/DotChatPanelTest.kt`。

**Interfaces:** Consumes `ChatViewModelStore.ownerFor(sessionId:String):ViewModelStoreOwner`、现有 `ChatViewModel.factory(...)`、`messages:StateFlow<List<ChatMessage>>`、`addAttachmentFromStagedShare(file:File):InputAttachment?`、`sendMessage(text:String)`、`cancelStream()`，Task3 capture。Produces `SessionBinding(sessionId:String,generation:Long)`；`DotChatAdapter.bind(sessionId:String):SessionBinding`, `attach(binding:SessionBinding,snapshot:ScreenSnapshot):Boolean`, `send(binding:SessionBinding,text:String):Boolean`, `cancel(binding:SessionBinding):Unit`；`DotInteractionCoordinator.handle(intent:DotIntent):Unit`；`@Composable DotChatPanel(adapter:DotChatAdapter,onCollapse:()->Unit)`。

- [ ] 写断言：切换A→B后`attach(bindingA,lateA)==false`；已确认A历史截图打开panel后仍绑定A；取消后迟到回调不新增消息；draft→canonical复用同一个VM；IME Back一次隐藏键盘、再次收面板，主应用同ID能见消息。
- [ ] 跑 UT/仪器测试先FAIL；mock HTTP只用于编码/错误断言。
- [ ] controller Single展示真实overlay panel；Double先捕获宿主再打开panel，显示带时间可撤销预览，用户发送时才上传。复用VM factory/store，private cache图片按取消/删除清理。显示stream/send/stop/retry/空与未配置状态，不能通过打开Activity替代浮层交付。
- [ ] 测试通过；网关计划Task6后追加设备真实文字/SSE和视觉：模型正确读出fixture宿主独特文字；保存脱敏请求ID/模型/结果，验证未包含Nanodot自己的输入框。
- [ ] DCO提交 `feat(android): chat and inspect screens from Dot`。

### Task5: 浮层语音 ownership 和合法前台录音

**Files:** Create `main/voice/VoiceLease.kt`, `DotVoiceSession.kt`, `DotMicrophoneService.kt`, `DotVoicePermissionActivity.kt`；Modify `AndroidManifest.xml`、既有 InlineVoiceInputPanel 的owner hook和 SpeechRecognitionManager（小hook）；Test `test/voice/VoiceLeaseTest.kt`, `androidTest/voice/DotVoiceSessionTest.kt`。

**Interfaces:** Consumes Task4 `SessionBinding`；现有 `SpeechRecognitionManager.startRecording(onPartialOrFinal:(String,Boolean)->Unit,onError:(RecognitionError,String?)->Unit)`, `stopRecording()/cancelRecording()` 及现有VoiceOutputState。Produces `VoiceLease.acquire(owner:String):Boolean/release(owner:String):Unit`；`DotVoiceSession.start(binding:SessionBinding):Unit/stop():Unit/cancel():Unit`；`DotMicrophoneService` 不接受任意未确认session，持有lease到停止。

- [ ] 写断言：`acquire("main")==true; acquire("overlay")==false`；release后可换owner；锁屏/失权/停止通知→IDLE+lease空；A语音晚到不能发送B；`continuousVoice()==false`不自行重录；拒绝权限→可用键盘/明确恢复入口。
- [ ] 跑UT/仪器测试先FAIL；测试 target35 的可见Activity启动与后台被禁止场景。
- [ ] 由可见PermissionActivity请求RECORD_AUDIO，并在可见时启动声明microphone的FGS和停止通知；再回宿主保持录音。录音前停止TTS，不同窗口共享lease；无权限/引擎/网络时显示实际错误。语音默认形成可编辑文字；主动连续模式遵循绑定generation与可见停止状态。
- [ ] 测试通过；真实Whisper中文短WAV转写与设备播放中文独立检查。云TTS未通过则明确选择并验证可用系统中文TTS，不假报云朗读成功。
- [ ] DCO提交 `feat(android): manage voice sessions for Dot overlays`。

### Task6: 轨迹圈选、遮罩与区域问答

**Files:** Create `main/selection/SelectionGeometry.kt`, `RegionCropper.kt`, `SelectionCoordinator.kt`；Modify Task2 trail、Task4预览；Test `test/selection/SelectionGeometryTest.kt`, `androidTest/selection/RegionCropperTest.kt`。

**Interfaces:** Consumes Task1 Drag、Task3 transform/DTO、Task4 adapter。Produces `RegionSelection(polygon:List<PointPx>,bounds:RectPx,areaDp2:Float)`；`SelectionGeometry.classify(points:List<PointPx>,density:Float):RegionSelection?`；`RegionCropper.crop(snapshot:ScreenSnapshot,selection:RegionSelection):Bitmap`；`SelectionCoordinator.finish(points:List<PointPx>):Unit`。像素填充even-odd；目标节点与选区相交才保留。

- [ ] 写断言：圆/矩形面积2304dp²与路径160dp边界；长往返细线返回null；even-odd自交面积按填充计算；密度1/2/3结果一致。红色机密图案在选区外：上传PNG解析后红色像素数=0。
- [ ] 跑UT/仪器测试先FAIL。
- [ ] 实现开放轨迹移动/贴边保存；闭合轨迹回原球位置，捕获同一场景，按polygon裁剪+遮罩选区外。展示预览/重选/取消/问题；捕获时宿主已变化则重选，不能把前页路径裁到后页。
- [ ] 测试通过，真实视觉仅回答选中fixture标签；验证字节和节点均未包含外部标记。
- [ ] DCO提交 `feat(android): ask about regions traced by Dot`。

### Task7: 独立、受约束的关怀 Planner

**Files:** Create `main/care/CareModels.kt`, `CarePlanner.kt`, `CareProposalValidator.kt`；Test `test/care/CareProposalValidatorTest.kt`, `CarePlannerTest.kt`。

**Interfaces:** Consumes Task3 `ScreenSnapshot` 与既有provider的只读chat调用。Produces `CareAction.Open/Focus/Copy/Paste/Search`；`CareExpectation.PageContains(text:String)/Focused(ref:String)/TextEquals(ref:String,text:String)/ResultsFor(query:String)`；`CareProposal(snapshotId:String,scene:SceneKey,targetRef:String,action:CareAction,instruction:String,copyText:String?,expectation:CareExpectation,confidence:Float)`；`CarePlanResult.Step(proposal:CareProposal)/Clarify(question:String)/Unavailable(code:String)`；`suspend CarePlanner.next(goal:String,snapshot:ScreenSnapshot,verified:List<CareProposal>):CarePlanResult`；`CareProposalValidator.validate(proposal:CareProposal,snapshot:ScreenSnapshot):Boolean`。

- [ ] 写断言：tools=[]且未调用Hands/dispatcher；旧snapshot/不存在target/password/越界/低confidence<0.7/任意script→reject；空节点只能Clarify；有效已有节点bbox才accept。model返回JSON畸形→Recovering错误而不是执行字符串。
- [ ] UT先FAIL，用FakeProvider完整保存request以断言 tools 空和未启用agent loop。
- [ ] 定义JSON契约与请求预算；目标必须来自当前可验证节点/关联区域，不接受裸坐标。调用独立provider chat，无自动fallback到有tools的VM，禁用输出执行工具。说明数据当引用上下文，不能当系统指令。
- [ ] UT通过；真实API返回一个fixture当前目标且schema通过；不以手工固定目标演示作为模型成功。
- [ ] DCO提交 `feat(android): plan one care instruction at a time`。

### Task8: 证据推进、暂停与恢复的关怀状态机

**Files:** Create `main/care/CareController.kt`, `CareVerifier.kt`；Modify Task2/3事件委托；Test `test/care/CareControllerTest.kt`, `CareVerifierTest.kt`。

**Interfaces:** Consumes Task7 proposal/expectation，Task3 snapshots。Produces `CareState.Idle/Observing/Clarifying/Guiding/AwaitingUser/Verifying/Completed/Recovering/Paused`；`VerificationResult.Confirmed/NotYet/NeedsUserConfirmation`；`CareVerifier.evaluate(proposal:CareProposal,next:ScreenSnapshot):VerificationResult`；`CareController.start(goal:String)/pause()/resume()/cancel()/onSceneChanged()/onAutomaticOperation(active:Boolean)`；`CareUiState(state:CareState,proposal:CareProposal?,goal:String,question:String?,errorCode:String?)`；`state:StateFlow<CareUiState>`。异步planner reply匹配task generation与其请求宿主SceneKey；host change立即清视觉目标，下一步Verifier允许预期的真实页面变化但必须重新校验宿主/目标证据。

- [ ] 写断言：无事件不推进；广告/误点/无关scroll→NotYet；正确focused/text/query结果且宿主匹配→Confirmed；不可读文字→NeedsUserConfirmation；Paused无框、resume重新capture；自动Hands/remote运行先pause；旧model回复丢弃。
- [ ] UT先FAIL；输入IME已显示时scene改变不得重新使用旧bounds。
- [ ] 事件仅触发debounced新采样；Verifier以当前已声明postcondition+package/window/目标证据确认，比较前后变化，不把采样前已经满足的条件当用户完成。节点ref限定snapshot；新snapshot按viewId/role/label重新关联，重复或不可确定则NeedsUserConfirmation，不依据固定等待或任意点击。后续page语义不确定时请求用户确认，不默默当成功；只记录已验证步骤，goal修改使旧计划失效。
- [ ] UT通过；instrumentation用同一页面中错误按钮/广告刷新验证不会下一步。
- [ ] DCO提交 `feat(android): advance care with observable evidence`。

### Task9: 关怀聚焦框、说明卡与交互动画

**Files:** Create `main/care/CareFocusLayer.kt`, `CareCard.kt`；Modify Task2/4 coordinator；Test `androidTest/care/CareOverlayTest.kt`；文案进`res/values*/nm_strings.xml`。

**Interfaces:** Consumes Task8 `state:StateFlow<CareUiState>`、Task7 proposal。Produces `@Composable CareCard(proposal:CareProposal,onCopy:()->Unit,onPause:()->Unit,onReobserve:()->Unit,onEnd:()->Unit)`；`CareFocusLayer.show(bounds:RectPx)/clear()`。复制由用户按按钮触发Android clipboard，不向宿主setText。

- [ ] 写Compose断言：屏幕每次只有一个目标，目标中用户touch到fixture；Copy可得proposal.copyText；Back→Paused/clear；fontScale2.0所有控制可达；TalkBack标签包含状态，focus框不抢焦点；animator duration scale0无循环动画。
- [ ] 仪器测试先FAIL。
- [ ] Triple独占进入紫色Dot响应与“我会一步步引导你”；18sp短说明/48dp按钮；非触摸focus描边随有效bounds定位，失效先clear，过渡尊重系统减弱动画。复制/重说/暂停/重新识别/结束保持可达。
- [ ] 测试通过；浅/深色、横/竖、100/150/200%字体截图审查。
- [ ] DCO提交 `feat(android): guide care with accessible focus overlays`。

### Task10: 虚拟机端到端、真实 API 与可交付 APK

**Files:** Create独立fixture app `android/dot-fixture/`（Kotlin DSL、Text/EditText/Button、manifest）、`scripts/android/verify-dot.sh`、`docs/dot-care.md`和`docs/zh/dot-care.md`；Test `androidTest/acceptance/DotAcceptanceTest.kt`；Modify Android CI targeted tests。

**Interfaces:** Fixture只暴露测试页面：Home→Delivery→SearchFocused→QueryEntered→Results(query)，另有WrongTarget、AdRefresh、SecureWindow；独特文字 `DOT_HOST_731`/`OUTSIDE_924`。无支付/账号/外部交易；正式APK不包含fixture或测试凭据。脚本参数 `verify-dot.sh <serial> <apk> <fixture-apk>`。

- [ ] 写端到端断言：1/2/3击只执行对应动作；圈选外标记不上网；关怀按上述5页推进，误点/广告停留，用户每点击后才有下一步；录音stop通知清理；服务关闭无任何窗；全部真实request有可追踪ID但无secret/body日志。
- [ ] 运行新验收得到当前缺功能的FAIL；独立保留 P0 ANR作为启动基线。
- [ ] 运行Task1–9、网关live验收后完整fixture流程；优化发现的启动/生命周期瓶颈并保留修复证据。先以可运行虚拟机实际测试，再在可用虚拟淘宝环境做用户搜索任务；平台缺失/第三方不可登录明列未验证，不伪造。
- [ ] `:app:testDebugUnitTest --tests 'io.github.nanomuse.*'`、`:app:connectedDebugAndroidTest`、`:app:assembleDebug`/现有signed-release脚本；APK签名/SHA/manifest验证、干净安装和保留设置升级测试。区分TCG/arm64 native/OEM/mic/第三方限制；不能仅有build green就关闭交互任务。
- [ ] 报告每项通过/失败/未验证、截图/录像、真实模型/调用ID；DCO提交 `test(android): verify Dot interaction journeys`，推送并保存新commit对应Actions APK。

## 执行依赖与自审记录

1→2→3→4 是第一款可用浮球+文字屏幕对话；5语音与6圈选共享4但互不改同一控制器时可并行；7→8→9→10关怀。网关Task1–5可与安卓基础并行，安卓Task4/5/7真实验收依赖网关Task6。每阶段生成可安装APK，安卓失败优先修复。

已逐段对照设计：浮球/生命周期2、聊天4、截图3、语音5、圈选6、关怀7–9、真实测试10；全界面导航在UI计划，网关/迁移在网关计划。类型/签名统一；五项Review Focus均有对应测试。MediaProjection作为26–29明确像素选项而非假称无障碍截图支持。未实现功能仍标未完成。
