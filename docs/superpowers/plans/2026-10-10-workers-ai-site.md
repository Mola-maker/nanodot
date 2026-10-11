# Workers AI Gateway and Website Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 molamaker.si 部署免费额度内的受鉴权模型网关与 Nanodot 网站，供安卓首先真实调用，并向其他平台提供一致配置。

**Architecture:** 一个 TypeScript Worker 绑定 AI 与静态ASSETS；仅 `/v1/*` 和 `/healthz` 进入API，其他路径服务静态主页与构建后的文档。单个SQLite Durable Object只保存鉴权客户端ID对应的限额计数，不保存对话/截图/音频；现有Python Cloud账号/Hub服务继续原身份，不迁移为伪Worker接口。

**Tech Stack:** Node22、TypeScript、Wrangler4、Workers AI binding、SQLite-backed Durable Object、Vitest Workers pool、静态HTML/CSS/JS、现有VitePress。版本在实施时从官方npm registry锁定，提交lockfile；不引入产品不需要的UI框架。

**Spec:** `../specs/2026-10-10-android-dot-care-design.md`；用户另已明确选择“先采用 Cloudflare Workers AI，限定免费额度”。

**已核实的部署前提（2026-10-11 00:21，Asia/Shanghai）：** 在用户已登录的Cloudflare账户内，经“计算 → Workers计划”只读查看，Free卡片显示US$0和禁用的“当前套餐”按钮；页面列出Workers AI 10,000 Neurons/day，及SQLite Durable Objects免费请求/SQL配额。不是仅由域名Free标识推断Workers套餐。molamaker.si DNS仍只有9项既有邮件记录，页面提示根域/www缺少网站入口；后续独立Worker的custom domain需新建，现有邮件和其他项目保留。此次没有修改套餐、DNS、资源或凭据，没有调用AI。该核验满足当前免费套餐前提，实际部署时仍确认没有套餐变更。

## Global Constraints

- 新模型origin=`https://molamaker.si`，OpenAI base=`https://molamaker.si/v1`；原账户、第三方provider/key与Cloud协议全部保留其真实origin。
- 只在**核验Workers账号确为Free**后部署启用推理；不购买/升级、不接受Paid模型或自动切Paid。Workers AI免费总10,000 Neurons/day、00:00UTC重置；这是Cloudflare账号硬限，不等于每个app用户无限免费。[官方定价](https://developers.cloudflare.com/workers-ai/platform/pricing/)
- Allowlist：`@cf/qwen/qwen3-30b-a3b-fp8`文字、`@cf/google/gemma-4-26b-a4b-it`视觉、`@cf/openai/whisper-large-v3-turbo` ASR；`@cf/myshell-ai/melotts`只在中文实际验证后公开TTS能力。allowlist拒绝任意客户端model/上游URL。
- 应用只用独立项目gateway credential；Cloudflare控制token只由部署环境持有。secret在Worker secret/客户端已有加密存储，禁止编进APK/网页、日志或文档。
- 第一期为持有项目凭据的接入，不虚构注册/登录/Hub/sync。不提供公开共享key、dummy key或未经限额的匿名AI网页。
- 应用策略（不是供应商硬限）：body≤4MiB，文本上下文≤16,000字符，输出≤2,048 tokens，单张图片≤2MiB/最长边1280px（客户端重编码），ASR≤30s/2MiB独立WAV，TTS≤500字符。客户端预算提示与服务端强检一致。
- 额外硬计数：所有AI路由总计≤40次/UTC日，每个授权客户端≤12次/滚动60s；调用前原子计数，取消/失败不退回计数。真正Neuron总额仍由Free账号硬限兜底，不把请求数当准确Neuron预算。
- 未经真实验收不改新安装默认连接；客户端显示需要项目凭据，不用旧Cloud token填入新服务。已有配置默认不覆盖。
- API错误返回non2xx或明确SSE error，绝不200 HTML/假转写；日志只记requestId、模型ID、状态、耗时和计数，不记body/key。
- 英文/中文、浅/深色、可核验下载与GPL/upstream归属；网站只宣称实际交付能力。P0 Actions的临时artifact下载需注明登录/保留期限，不能冒充永久公开Release。

## Review Focus

- 两个不同地域同时打满额度：原子全局计数≤40，失败不调用AI（Task1）。
- Qwen原生response片段、中文UTF8跨chunk、截断/error流：正规SSE或诚实失败，Android不静默空白（Task2）。
- 恶意远程image_url、超大/假WAV和不支持格式：拒绝且不抓任意URL、不推理（Task2/3）。
- 旧Cloud有token但没base键、异步配置初值空：保留旧origin，不能当fresh转发旧凭据（Task5）。
- 静态asset优先路由或缓存误命中API：/v1返回API错误，auth/private响应禁止缓存，未知网页真实404（Task4/6）。

---

## 文件边界与测试命令

新服务独立目录 `gateway/`，不改 `cloud/` 以假装协议兼容。`gateway/src/`按auth/quota/chat/audio/assets职责拆文件。`npm --prefix gateway run check`=tsc+Vitest；`npm --prefix gateway run deploy:dry`=Wrangler deploy --dry-run；Workers测试使用官方pool与fakeAI，不在单元测试偷偷访问账号额度。live验证用另列脚本/凭据、计入40次总预算。

### Task1: 闭合鉴权、限额与Free资源配置

**Files:** Create `gateway/{package.json,package-lock.json,tsconfig.json,vitest.config.ts,wrangler.jsonc}`, `src/{index.ts,auth.ts,quota.ts,types.ts}`, `test/{auth.test.ts,quota.test.ts}`、`docs/gateway.md`/`docs/zh/gateway.md`。

**Interfaces:** Produces `GatewayEnv { AI:Ai; ASSETS:Fetcher; FREE_GATE:DurableObjectNamespace<FreeGate>; GATEWAY_KEY_HASHES:string }`；hashes为secret中`{clientId:sha256Hex}`，不保存原key。`authenticate(request:Request,env:GatewayEnv):Promise<{clientId:string}|null>`；`FreeGate.admit(clientId:string,nowMs:number):Promise<{allowed:boolean;retryAfterSeconds:number}>`；`handle(request:Request,env:GatewayEnv,ctx:ExecutionContext):Promise<Response>`。missing/invalid auth→401、缺secret/binding→503 fail closed。

- [ ] 写断言：missing/wrong/key-query→401且AI calls=0；missing config→503；并发41次只有40 allowed；minute第13次→429；跨UTC日重置；重复失败也已计数；secret/rawbody不会出现在logger。
- [ ] `npm --prefix gateway test -- auth quota` 先FAIL。
- [ ] 实现Bearer SHA256对比、SQLite-backed `FreeGate`全局对象原子计数+每client60s窗口（nowMs仅Worker Date.now()，不采信客户端时间），不使用最终一致KV做硬预算。配置AI、ASSETS、new_sqlite_classes迁移；禁用body日志，health只说明部署状态不触发AI。FreeGate单对象记录固定授权client与当日/分钟数，过期清计数；无chat存储。
- [ ] tests+typecheck PASS，dry-run无secret；部署前实际Dashboard/账户Plan核验Free。若非Free，保持推理禁用并请用户提供Free目标，不自行更改别的项目billing。
- [ ] DCO提交 `feat(gateway): enforce private free-tier access`。

### Task2: 模型列表、Chat、SSE与图片协议

**Files:** Create `gateway/src/{models.ts,chat.ts,sse.ts,errors.ts}`, `test/{models.test.ts,chat.test.ts,sse.test.ts}`；Modify `index.ts` routes；协议进 docs/gateway 双语。

**Interfaces:** Consumes Task1 env/auth/admit。Produces `ModelSpec(id:string,inputModalities:readonly string[],outputModalities:readonly string[])`；`listModels(ttsVerified:boolean):Response`；`ChatRequest(model:string,messages:unknown[],stream:boolean,max_completion_tokens?:number,max_tokens?:number,tools?:unknown[])` 经验证后调用；`handleChat(request:Request,env:GatewayEnv,clientId:string):Promise<Response>`；`normalizeAiStream(source:ReadableStream<Uint8Array>,model:string,signal:AbortSignal):ReadableStream<Uint8Array>`。GET `/v1/models`为`{object:"list",data:[{id,name,architecture:{input_modalities,output_modalities}}]}`。

- [ ] fixture断言真实Android OpenAIModelsApi可读Qwen text/Gemma text+image/Whisper audio→text；纯文字模型遇图→400。SSE `choices[0].delta.content`、reasoning/tool_calls/usage、stop/length、`[DONE]`；分段中文字节还原同原文；原生`response`正规化；截断→error，不伪造正常stop。
- [ ] gateway tests先FAIL；Android新增 `io.github.nanomuse.dot.gateway.GatewayProtocolTest` 通过真实ProviderFactory/streamMessage，不能只测自造client。
- [ ] 输出绑定model-specific adapter：Qwen `max_completion_tokens→max_tokens`，Gemma规范choices；非流标准choices.message，流标准chunks+usage+DONE。接受验证过的文本/dataURL图像，不fetch远程image_url；预算/非法model/格式先拒绝再admit。只传allowlisted参数；普通agent的function工具定义/结果按模型实际能力编码与回传，网关不执行；Care请求tools=[]。
- [ ] 两端测试PASS；HTTP401/429/503/error-stream、空流、取消/reader.cancel释放流明确测试。取消客户端不能保证平台中止已开始计费，额度不退款。
- [ ] DCO提交 `feat(gateway): adapt Workers AI chat protocols`。

### Task3: 真实ASR与中文TTS门禁

**Files:** Create `gateway/src/{audio.ts,wav.ts}`, `test/{audio.test.ts,wav.test.ts}`, `scripts/gateway/verify-audio.mjs`；Modify models/index routes和Android voice cancellation hook（上游文件加注释）；Test Android `dot/gateway/GatewayVoiceTest.kt`。

**Interfaces:** Consumes Task1 env/admit与Task2 errors。Produces `parseWav(data:Uint8Array):{sampleRate:number;channels:number;durationSeconds:number}`；`handleTranscription(request:Request,env:GatewayEnv,clientId:string):Promise<Response>`；`handleSpeech(request:Request,env:GatewayEnv,clientId:string):Promise<Response>`。ASR `/v1/audio/transcriptions` multipart `file/model/language/response_format=json`→`{text,language?,duration?}`；TTS `/v1/audio/speech` JSON→audio/mpeg bytes，仅`TTS_ZH_VERIFIED=true`时开放并列入models。

- [ ] 写断言：16kmonoPCM16有效WAV、zh-CN→zh、错误RIFF/空/31s/超2MiB→400/413；errorJSON不能当转写；TTS voice=`model.id`合法映射、未知voice/format/speed明确拒绝；未验证中文→503且catalog无audio output；cancellation取消OkHttp并丢迟到generation。
- [ ] 两端test先FAIL。
- [ ] ASR将有界完整WAV→base64给Whisper，task=transcribe。中文MeloTTS用官方支持参数做小量实测并保存准确lang；验证后固定该lang与项目default voice alias，不忽略不支持speed/format。真实MP3解码/时长/中文听感同时通过才置能力标记。未通过维持禁用，安卓明确选用且验证系统中文TTS继续朗读；不把设备TTS写成云TTS。
- [ ] testsPASS；verify-audio用已有本地非私人短中文WAV→真实转写；中文/中英混合合成→实际可播放并人工听验。结果计入总live预算，不输出凭据/私人录音。
- [ ] DCO提交 `feat(gateway): serve bounded voice requests`。

### Task4: Nanodot首页、接入、下载与文档

**Files:** Create `site/{index.html,style.css,site.js,privacy.html,connect/index.html,404.html,downloads.json}`, `site/assets/`离线Dot；`scripts/site/build.mjs`；`site/tests/{routes.test.mjs,content.test.mjs}`；Modify `website/.vitepress/config.mts` 的可配置新部署base。

**Interfaces:** Consumes 已交付P0 manifest、Task2/3能力及旧上游主页结构。Produces `buildSite({outDir:string,docsDir:string}):Promise<void>`；`downloads.json` rows `{platform,label,url,sha256,status:"available"|"unsigned"|"pending",expiresAt?:string}`。输出 `gateway/public/`含 `/docs/`；`wrangler.jsonc` ASSETS.directory指此，run_worker_first=["/v1/*","/healthz"]，API无静态回退。

- [ ] content test断言Repo=Mola-maker/nanodot、Dot离线、中英/明暗、无原demo/TestFlight冒称；unsigned iOS明确不能直接安装；未实现orb/care标预览；路径404不是首页200；v1/models不匹配static。所有download rows对应已校验实际文件/产物，sha格式64hex。
- [ ] test先FAIL；纯文案/间距无需镜像式单测。
- [ ] 参考 nano-muse.github.io 的轻量静态结构重写文案/视觉；用统一tokens/systemfont、语义header/nav/main/footer；手机stack、desktop2列但不嵌原demo；connect页只解释项目凭据与base，不收CF token。隐私说明屏幕/音频按用户发送才交CF、项目不持久存储payload、供应商政策链接。docs VitePress构建拷贝，下载优先Android，其余实际可用包/临时artifact诚实显示。
- [ ] `node --test site/tests/*.test.mjs`、`npm --prefix website run docs:build`、buildSite PASS；浏览器360/768/1440、200%字体、键盘、reduced-motion、浅深/中文英文人工视检。
- [ ] DCO提交 `feat(site): publish Nanodot product pages`。

### Task5: 各平台新服务preset与凭据origin隔离

**Files:** Modify source `nanomuse/llm/providers.json`（id=`nanodot-workers`，protocol=openai，base_url=`https://molamaker.si/v1`，auth=[key]，capabilities=[chat,vision]）；run `scripts/providers-json.mjs` 再生成全部copies。Create Android `android/src/android/app/src/main/java/io/github/nanomuse/dot/gateway/GatewayConnection.kt`/`GatewayOriginPolicy.kt`，测试对应`src/test/java/io/github/nanomuse/dot/gateway/GatewayConnectionTest.kt`/`GatewayOriginPolicyTest.kt`；iOS `android/src/ios/NanoMuse/NanodotGateway.swift`及`MinisTests/NanodotGatewayTests.swift`；desktop `harness/dsh-nanomuse/src/client/NanodotConnection.tsx`、`src/gateway-origin.ts`及`tests/gateway-origin.test.mjs`；Python `nanomuse/llm/gateway_profile.py`及`tests/test_gateway_profile.py`。修改各平台已有own-key/onboarding入口，小hook不复写Cloud。

**Interfaces:** Android Produces `GatewayConnection.connect(credential:String):Unit`：新UUID ProviderInstance(label="Nanodot · Workers AI",providerType=openAI,credentialType=apiKey,customBaseURL="https://molamaker.si",appendV1Suffix=true,useResponsesAPI=false,azureMode=false)，通过 ProviderRepository.addInstance/saveApiKey/refreshModels。纯policy `GatewayOriginPolicy.isFresh(configLoaded:Boolean,hasAccount:Boolean,hasProvider:Boolean):Boolean`。其余平台同等新provider origin/id/加密凭据绑定；无旧token转移。新安装推荐网关接入卡，用户提供专用key后才调用，不放公共key。

- [ ] fixture断言old implicitCloud+token/config未加载→非fresh且oldorigin unchanged；explicit custom/cloud/provider/avatar/groups均保留；重复connect/restart不重复新实例；错误key refresh保留旧有效models；root/root slash/v1/v1 slash构造chat/models/ASR/TTS均只有一个/v1。
- [ ] 各平台新增迁移测试先FAIL；用真实Android repository fixture与OpenAI/voice URL composer断言，不仅测试string helper。
- [ ] 新独立preset/连接UI等待configLoaded，专用key存现有加密repo；已配置用户只见可选接入，不自动换组选中的模型。Cloud old默认仍旧origin；若以后需改变Cloud默认，先绑定旧implicit origin才迁移，不在本期实施。模型实际modalities决定vision/voice entry，中文TTS门禁服从live结果。
- [ ] Android targeted UT、Swift相关tests（mac runner）、plugin typecheck/tests、Python相关tests、`node scripts/providers-json.mjs --check` PASS；key不会出现在APK/Web静态扫描。各平台至少真实模型list/textSSE验收，不能用Android成功代替另外三端。
- [ ] DCO提交 `feat(clients): add Nanodot gateway connection`。

### Task6: 部署、真实端到端与回滚

**Files:** Create `scripts/gateway/verify-live.mjs`、`.github/workflows/gateway-site.yml`、`docs/gateway-operations.md`/`docs/zh/gateway-operations.md`；输出脱敏live报告/请求ID/产物SHA，不提交测试key。

**Interfaces:** `verify-live.mjs --base https://molamaker.si --report <path>` 从安全本地env读取 `NANODOT_TEST_GATEWAY_KEY`，不从argv/chat接收secret；验证所有公开/鉴权路由和能力。CI只lint/test/build/dryrun，deployment只workflow_dispatch+受保护environment；CF部署token为最小必要资源权限，不创建宽权限token作为快捷方案。

- [ ] 写集成断言：health不调用AI；unauthmodels→401；api不存在→JSON404且no-store；未知网页404；CORS仅molamaker.si/www，preflight无鉴权但不推理；跨host重定向不带Bearer；auth/error私密响应不cache；网站未嵌secret。mock配置/route检查先FAIL。
- [ ] 测试PASS并完成静态/dryrun可审查版本后，使用已打开Cloudflare账号或用户授权的部署CLI；需要新控制权限/credential授权时在具体资源与权限已可审查后交用户确认，不预先请求笼统升级。确认Workers Free；创建独立nanodot Worker/SQLite DO/AI/ASSETS，设置项目hash secret。绑定根域molamaker.si，www只301到根；保留所有邮件记录与已有Worker。
- [ ] 保存Worker version/route/DNS记录差异、部署commit与回滚命令；TLS有效后用≤15次真实AI请求验text中文SSE、视觉fixture中文标记、中文ASR、条件TTS、无tools关怀proposal、取消、401/429。Free耗尽使用mock和真实计数拒绝测试，不故意消耗10,000Neurons；容量/每日供应商实际超额尚未遇到需如实标未实测。
- [ ] Android浮层实际调用新origin，desktop/web/iOS own-provider分别真实协议验收；同commit重新构建Android优先，再desktop矩阵+iOS archive；旧服务配置升级保留。失败回滚Worker version或关闭AI入口，客户端给恢复入口；不删除旧账户/DNS邮件。
- [ ] 报告网站、API、文字、视觉、ASR、TTS分别PASS/FAIL/未验证及用量；DCO提交 `ci(gateway): verify and deploy the free-tier service`，按已授权范围推送，不自动发布Release/TestFlight。

## 执行顺序与自审记录

Task1→2（文字/视觉服务）与Task4（网站）独立；Task3声音→Task5接入→Task6部署/真实验收。Task6实际部署先于安卓Task4/5/7 live检查，不能在mock通过后声称服务已可用。仅将已通过模型放入published catalog；中文TTS未知是明确门禁而非“稍后处理”。

自审：全部design服务/网站/迁移要求都有对应任务；SSE、voice=model.id、Repository.configLoaded、静态routing与全局原子限额均有测试；schema与类型在产生任务定义。该期独立交付working模型网关与网页，不承担没有实现的NanoMuseCloud账户/Hub兼容。

官方实施依据：[AI bindings](https://developers.cloudflare.com/workers-ai/configuration/bindings/)、[OpenAI compatibility](https://developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/)、[SQLite DO Free](https://developers.cloudflare.com/durable-objects/platform/pricing/)、[static assets](https://developers.cloudflare.com/workers/static-assets/)、对应[Qwen](https://developers.cloudflare.com/workers-ai/models/qwen3-30b-a3b-fp8/)/[Gemma](https://developers.cloudflare.com/workers-ai/models/gemma-4-26b-a4b-it/)/[Whisper](https://developers.cloudflare.com/workers-ai/models/whisper-large-v3-turbo/)/[MeloTTS](https://developers.cloudflare.com/workers-ai/models/melotts/)模型schema。
