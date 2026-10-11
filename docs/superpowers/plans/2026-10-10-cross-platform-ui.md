# Nanodot Cross-platform UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将安卓全应用、Windows/macOS/Linux桌面、网页控制台和iOS统一为Dot设计系统，保留已有功能与各平台导航习惯。

**Architecture:** 一个可验证token源生成Kotlin/Swift/CSS；各平台保留已有原生框架与数据层。先完成Android导航/组件，再将同一视觉与状态规范应用到React/Electron与SwiftUI；Kotlin/Compose仅用于Android，桌面与iOS不进行没有必要的框架重写。

**Tech Stack:** Android Kotlin/Compose/Material3/Kotlin DSL；桌面React/TypeScript/Electron；Web Vite/React；iOS SwiftUI/iOS16；Python生成器、Compose UI tests、浏览器Playwright、XCTest/Actions。

**Spec:** `../specs/2026-10-10-android-dot-care-design.md`；浮球/关怀在安卓交互计划，新网关连接在网关计划。

## Global Constraints

- Android优先，现有存储/session/model/provider/key/avatar/主题保存；品牌生成幂等，既有包、namespace、CLI和sandbox不改。
- 颜色沿`config/brand.nanodot.json`：light background F4F7F8/surface FFFFFF/text16252C/primary136F63/onPrimaryFFFFFF/care7057A3/outline687980；dark10191D/182329/E5EDF0/7DD6C5/10191D/C7B4F7/8EABB5。
- 系统字体，body16sp/关怀18sp；space4/8/12/16/24/32/48；Android触控≥48dp，其余平台使用原生可访问最小触控尺寸，指针平台也保留大按钮。
- Phone底部导航/单列，宽窗口导航rail/会话列表+对话双列；Android<600dp compact、600–839dp medium、≥840dp expanded作为此计划实现阈值，字体/IME可减少实际pane。
- 主导航Chat/Tasks/Devices/Settings；Feed/Ideas/Goals映射Tasks，Library由Tasks内“资料”入口保留。现有终端、模型、技能、连接器、记忆、文件、备份等从Settings或现有上下文入口继续可达。
- 所有button/input/dropdown/switch/checkbox/card/list/dialog/sheet均明确enabled/disabled/pressed/loading/success/error/empty/retry。颜色不作为唯一信息；成功/失败文本与icon/state announcement对应。
- 页面转场180ms、按压100ms、focus200ms；系统reduced-motion/durationScale0则静态，不循环闪烁、不抢TalkBack焦点。
- 100/150/200%字体、浅深色、横屏/分屏、手机/平板、键盘与屏幕阅读器逐项验证；发送/停止和返回永远可达。
- 新文案覆盖已有所有locale，英文/简中docs成对；upstream hook标注；行为更新CHANGELOG；DCO commits。无未经核验外部字体/原demo/TestFlight替代自有内容。

## Review Focus

- Navigation切换期间草稿/流式/滚动丢失：同session保持VM/store与UI状态（Task2）。
- 200%字体+IME+窄横屏：发送/停止/关闭仍可达，内容可滚动（Task2/3）。
- 多窗口resize/RTL/返回deep link：当前route与焦点稳定，隐藏功能仍可到达（Task2/4/6）。
- 相同绿色按钮深色onPrimary/disabled状态对比不足：语义token而非硬编码，文本对比≥4.5:1（Task1）。
- 首次离线或新模型服务401/429：离线Dot可见，准确重试/接入，不能无限loading或假成功（Task3/4/6）。

---

## 文件与状态契约

生成输入颜色保留`config/brand.nanodot.json`，新增`config/ui.nanodot.json`只定义spacing/type/motion/radius，避免第二份颜色源。每平台UI组件呈现state，业务操作仍调用现有Repository/controller。

### Task1: 同源tokens与原生组件规范

**Files:** Create `config/ui.nanodot.json`, `scripts/gen-ui-tokens.py`, `tests/test_ui_tokens.py`, `docs/design-system.md`/`docs/zh/design-system.md`；Generate Android `io/github/nanomuse/dot/ui/DotTokens.kt`, iOS `NanoMuse/NanodotTokens.swift`, `web/src/dot-tokens.css`, desktop `src/client/dot-tokens.ts`, `site/dot-tokens.css`。Modify现有`DotColors.kt`/Theme hook由generator消费已有颜色源，不手改生成文件。

**Interfaces:** Produces `generate_ui_tokens(brand_path:Path,ui_path:Path,check:bool=False)->bool`；Kotlin `DotTokens.Space/Type/Motion`；Swift `NanodotTokens`；CSS `--dot-background/surface/text/primary/on-primary/care/outline`与`--dot-space-*`。组件规范列主/次/icon按钮、文本输入/dropdown/switch/checkbox、cards/list、dialog/sheet/nav所有状态，Android复用M3语义行为。

- [ ] 断言所有输出tokens与source一致、二次生成无diff、无效颜色/负间距先FAIL不改文件；light/dark text-on-surface/primary ≥4.5 contrast；UI输入source的16/18、48、180/100/200准确。
- [ ] `python -m pytest tests/test_ui_tokens.py -q`先FAIL。
- [ ] 实现小generator与明确组件文档；AndroidMaterialTheme映射完整on-colors，按钮不使用错light onPrimary；组件error/retry等语义文字由平台localized字符串提供。Dot离线头像为默认视觉，无网络字体/视频前置。
- [ ] tests与`python scripts/gen-ui-tokens.py --check` PASS；Compose组件gallery渲染浅深色/disabled/error与200%字体，人工检查真实截图。
- [ ] DCO提交 `feat(ui): share Nanodot design tokens`。

### Task2: Android全应用导航与宽屏架构

**Files:** Create `io/github/nanomuse/dot/ui/{DotHomeRoute.kt,DotHomeShell.kt,DotTaskHub.kt,DotNavigationPolicy.kt}`；Modify `io/github/nanomuse/ui/home/{NanoMuseHome.kt,HomeTab.kt,MuseBottomBar.kt,SideChatDrawer.kt}`、upstream`ui/navigation/AppNavigation.kt`小hook、`res/values*/nm_strings.xml`；Test `src/test/.../dot/ui/DotNavigationPolicyTest.kt`, `src/androidTest/.../dot/ui/DotHomeShellTest.kt`。

**Interfaces:** Consumes existing ChatViewModelStore、navController、既有FeedTab/IdeasTab/GoalsTab/LibraryTab与Devices/settings routes。Produces `DotHomeRoute.Chat/Tasks/Devices/Settings`；`DotNavigationPolicy.layout(widthDp:Int,fontScale:Float):DotLayout`，`DotLayout.Compact/Medium/Expanded`；`@Composable DotHomeShell(route:DotHomeRoute,onRoute:(DotHomeRoute)->Unit,content:@Composable()->Unit)`；Tasks内subroute保留旧HomeTab映射，不改旧deep-link数据协议。

- [ ] 断言home四入口都可达；Tasks→Feed/Ideas/Goals/Library往返保持原功能；A chat草稿+streaming切task后返回仍在；width599/600/839/840确定布局，fontScale2.0无不可达按钮；deep link+Back回正确host而非重复app退出。
- [ ] targeted UT和connected UI tests先FAIL。
- [ ] 按阈值实现phone bottomnav/medium rail/expanded list-detail；保留已有会话VM owner和canonical aliases，按pane自适应而非机型。header简洁Dot+状态+新会话，主导航有文字标签；保留资料/备份/终端/模型/权限等已有入口与返回。状态存rememberSaveable，IME/cutout/insets真实消费。
- [ ] 360×800、800×360、600/840/1200dp，字体1/1.5/2，RTL与分屏实际resize测试PASS；每主要路径截图和返回逻辑记录。
- [ ] 双语docs与CHANGELOG同改；DCO提交 `feat(android): unify Nanodot app navigation`。

### Task3: Android各页面、组件状态与动画落地

**Files:** Create `dot/ui/{DotPageState.kt,DotStatusSurface.kt,DotChatChrome.kt,DotComponentGallery.kt}`；Modify现有`io/github/nanomuse/ui/goals/GoalsTab.kt`、`ideas/IdeasTab.kt`、`feed/FeedTab.kt`、`library/LibraryTab.kt`、`models/ModelsScreen.kt`、`hands/HandsScreen.kt`、`reach/ComputersScreen.kt`、`cloud/CloudAccountScreen.kt`、`cloud/CloudSignInScreen.kt`、`net/NetworkScreen.kt`、`profile/AgentProfileScreen.kt`、`avatar/AvatarStudioScreen.kt`的tokens/chrome小块；同上ui包前缀；上游`com/openminis/app/ui/chat/ChatScreen.kt`、`ui/settings/SettingsScreen.kt`和`ui/theme/Theme.kt` hooks；Test `androidTest/.../dot/ui/DotPageStateTest.kt`, `DotAccessibilityTest.kt`。

**Interfaces:** Produces `DotPageState.Loading/Ready/Empty(message:String)/Error(message:String,retryable:Boolean)`；`@Composable DotStatusSurface(state:DotPageState,onRetry:()->Unit,content:@Composable()->Unit)`；`DotChatChrome`消费真实VM状态而不复制agent逻辑。浮层Task4/9使用相同tokens，主应用重新设计不修改其协议。

- [ ] UI断言每page有可见真实Loading/Empty/Error+Retry；model401/429/离线错误可恢复无无限spinner；glyph labels/TalkBack state正确；控件bounds≥48dp；大字+键盘send/stop可见；成功不只绿色、失败不只红色。
- [ ] connected测试先FAIL；纯样式不增加镜像实现的单测。
- [ ] 使用M3 Button/OutlinedButton/IconButton/TextField/DropdownMenu/Switch/Checkbox、Card/LazyList/AlertDialog/ModalBottomSheet；逐页换统一spacing/type/chrome而保留业务。chat/任务/设备/设置及其子页全部检查；动画100/180/200ms/reducedmotion静态，加载可取消、失败恢复到同输入状态。
- [ ] TalkBack真实遍历、RTL、大字、横屏/输入、全locale字符串检查；Compose测试+Android当前targeted UT PASS。关怀焦点/卡不以全屏可触摸层阻断应用操作。
- [ ] DCO提交 `feat(android): apply accessible Nanodot components`。

### Task4: Windows/macOS/Linux桌面和Web统一UI

**Files:** Modify desktop `harness/dsh-nanomuse/src/client/{styles.ts,ui.tsx,MuseSidebar.tsx,MuseChats.tsx,MuseSettings.tsx,DevicesPanel.tsx,GoalsPanel.tsx,FeedPanel.tsx,LibraryPanel.tsx,ComposerExtras.tsx,Onboarding.tsx,locales.ts}`；Web `web/src/{App.tsx,index.css,theme.ts,screens/ChatScreen.tsx,screens/SettingsScreen.tsx,screens/DevicesScreen.tsx,screens/FeedScreen.tsx,screens/IdeasScreen.tsx,screens/GoalsScreen.tsx,screens/LibraryScreen.tsx,screens/Onboarding.tsx,i18n/index.ts,i18n/zh-CN.ts}`；Create对应 `DotStatus.tsx`与`tests/dot-navigation.test.mjs`、`web/src/dot-navigation.test.ts`、`scripts/ui/verify-web.mjs`和Playwright browser验收fixtures。

**Interfaces:** Consumes Task1生成tokens；已有React panels/session/query/stores不换。Produces桌面/Web `DotStatus({state:"loading"|"empty"|"error"|"ready",message:string,onRetry?:()=>void}):ReactNode`；四nav聚合与Android相同功能映射，保留dsh renderSlot及全部panel IDs/快捷键/文件drop与native窗口控制。

- [ ] 导航行为测试：四nav与旧rooms全可达、设置/设备分组正确、Ctrl/Cmd+K/newchat/back工作、输入草稿不因route切换丢；401/429明确；offlineDot显示。Playwright断言320/768/1440和200%scale keyboard可达、resize不重复提交、reducedmotion无循环。
- [ ] 新行为测试先FAIL；source text fences不能代替实际DOM/keyboard行为测试。
- [ ] 将tokens用于整体rail/chat/sidebar/dialog/composer/forms/list/settings，Tasks聚合旧rooms；Web保持relative `/api/cloud` runtime接口，不误替成外部/v1。Windows/Linux尊重native窗口控制，Mac尊重Cmd；保留拖文件、原生复制/快捷键和减少动画偏好。页面真实state不通过静态mock伪装。
- [ ] plugin pnpm build/typecheck/test，shell npm typecheck/test，web npm check/build PASS；提交生成 `nanomuse/server/static/`。实际浏览器/本地LinuxElectron启动和resize/输入；Win/Mac Actions build+smoke分别跑，额外安装器/GUI未测试明列。
- [ ] DCO提交 `feat(desktop): unify Nanodot navigation and states`。

### Task5: iOS Simulator 原生依赖与独立缓存

**Files:** Create `scripts/ios-native-target.py`, `tests/test_ios_native_target.py`；Modify `android/deps/{build_lame.sh,build_ffmpeg.sh,build_ish.sh}`、`android/src/ios/Minis.xcodeproj/project.pbxproj`、`.github/workflows/ios-check.yml`；既有`build_rclone_ios.sh`保留device/simulator双slice。

**Interfaces:** Produces `resolve_ios_native_target(platform:str)->dict[str,str]`，只接受`iphoneos`/`iphonesimulator`，返回sdk/minFlag/outputSuffix；default iphoneos、simulator使用`-mios-simulator-version-min=16.0`、device使用`-miphoneos-version-min=16.0`。shell通过`IOS_SDK_PLATFORM`选目标；各native output按SDK分目录，Xcode按SDK选对应slice，不能用同为arm64的device库冒充simulator库。

- [ ] 写断言：未知SDK先拒绝；sim/device输出dir与编译minflag不同；mock工具链捕获的sysroot/clang命令正确、旧default不变；实际Mac `vtool -show-build`每个slice的平台必须对应IOS或IOSSIMULATOR。
- [ ] `python -m pytest tests/test_ios_native_target.py -q`先FAIL；现device-only iSH/FFmpeg阻止完整Simulator链接的事实单独记录，不当作UI行为测试结果。
- [ ] 参数化native scripts、按SDK隔离LAME依赖/输出/缓存，将所需libs/frameworks组织为device+simulator XCFramework或SDK条件引用；Rclone使用已有simulator slice。公开header/ABI不变，iOS最低16.0；仅UI测试不宣称Simulator已验证真机JIT/硬件codec。
- [ ] Python tests PASS；Mac Actions真实build两套natives和Simulator app，所有slice平台校验通过，再archive device回归。缓存key含SDK/架构/源码/工具链，不能命中device-only缓存后跳Simulator构建。
- [ ] DCO提交 `build(ios): provide simulator native dependencies`。

### Task6: iOS原生设计同步

**Files:** Create `android/src/ios/NanoMuse/{NanodotHomeShell.swift,NanodotStatusView.swift,NanodotNavigation.swift}`；Modify `Views/ContentView.swift`/`Views/Chat/AIChatView.swift`小hook、现有NanoMuse页面tokens、`Localizable.xcstrings`；Test `MinisTests/NanodotNavigationTests.swift`, `MinisUITests/NanodotJourneyTests.swift`（独立UI test target若上游未有）。

**Interfaces:** Consumes Task1 Swift tokens、Task5 Simulator构建与现有ChatStore/ViewModelCache；Produces `enum NanodotRoute { case chat,tasks,devices,settings }`，`NanodotHomeShell:View`、`NanodotStatusView:View`。同一saved会话/key与原生NavigationStack/兼容split结构，iOS16 API遵循`.nmOnChange`。

- [ ] XCTest/UITest断言4nav+旧Feed/Ideas/Goals/Library功能可达；大字/键盘Composer可用；voice拒权有返回；离线/401错误可重试；切route不取消当前VM/草稿；无已通过的云中文TTS不能显示可用。
- [ ] macOS runner测试先FAIL；UI target仅在真实Simulator执行，不用generic device archive冒称UI测试。
- [ ] 应用SwiftUI tokens/动态字体/native sheet/list/forms/toolbar/keyboard，无Compose重写；phone tabs/大屏split匹配功能映射；保持原生返回与辅助功能标签。iOS系统限制不会允许Android式跨应用浮球，网站/应用文案按实际平台能力说明。
- [ ] macOS Actions跑iOS16+兼容构建门禁、最新可用Simulator UI tests、generic device archive，NanoMuse/零warning；device签名/profile未提供则产物仍明确unsigned，未做真机UI不说做过。
- [ ] DCO提交 `feat(ios): apply the Nanodot design system`。

### Task7: 同commit跨平台回归与交付

**Files:** Create `docs/ui-validation.md`/`docs/zh/ui-validation.md`、`scripts/ui/validation-matrix.json`；Modify existing build workflows输出commit/测试摘要/截图artifact。

**Interfaces:** 验收matrix `{platform,commit,build,install,ui,accessibility,api,evidence,limitations}`，状态只用pass/fail/unverified；APK/installer hash与版本不依赖手写推断。

- [ ] 先生成覆盖缺失字段即失败的matrix schema检查；未执行项不能填pass；source commit对应每个产物。
- [ ] 跑schema先FAIL，再从Task1–6实际日志/截图导出matrix与双语报告。
- [ ] Android先签名/哈希/新装/保设置升级和模拟器交互；desktop四matrix build/runtime/Harness smoke；iOS archive/Simulator；site/browser/DNS/TLS/API分开确认。Android/native bridge/无KVM等限制逐条写证据，不让桌面build成功遮掩Android交互失败。
- [ ] fresh整体review通过后只推送已授权代码；所有相应Checks有终态，缺签名/设备测试保留unverified。P0失败基线与新修复结果都存，功能Demo仅使用已实际通过流程。
- [ ] DCO提交 `test(ui): record cross-platform Nanodot validation`；不自动发布商店/TestFlight/Release或修改版本。

## 自审记录

设计系统/色彩排版间距/组件状态Task1&3；导航与历史功能Task2；跨桌面/Web/iOSTask4&6；Simulator原生依赖Task5；加载失败/动画/字体/无障碍全部有真UI验收；构建与结果真实性Task7。五项Review Focus均归具体任务，现有框架与storage保留。视觉纯编辑只进行渲染检查，新增测试聚焦导航丢状态、可访问性和协议错误等真实行为。
