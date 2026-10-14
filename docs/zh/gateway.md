# 私有 Workers AI 网关

`gateway/` 提供私有鉴权、持久化免费额度准入计数、白名单模型列表和聊天/视觉协议适配。配置有效时，聊天处理器可以尝试推理。有界WAV转写可以尝试推理；中文合成在真实验收前仍返回 `503 tts_unverified`，不消耗准入额度。产品站源码单独构建到静态资源暂存目录；源码完成不代表已经部署上线。此项工作不更改已有 App 连接或 nanoMuse Cloud。

预定 origin 为 `https://molamaker.si`，OpenAI base URL 为 `https://molamaker.si/v1`。这只是预定部署地址，不表示已验收线上服务。已有 Cloud 和第三方服务商保留真实 origin。

## 项目凭据

操作者向获授权的客户端提供独立项目凭据。此网关不创建账户、注册、Hub 或会话同步。Cloudflare 控制 token 只能由部署环境持有，不可当作项目凭据输入客户端。

Worker secret `GATEWAY_KEY_HASHES` 是一个 JSON 对象，将稳定且非空的客户端 ID 映射到项目凭据的 64 位十六进制 SHA-256 摘要。原始凭据只能保存在客户端已有加密存储中。禁止公开共享 key、编入 APK 或网页，或写入源码、日志和文档。重复摘要、格式错误和空配置都会拒绝服务。为不同客户端分配不同凭据；服务端通过匹配摘要确定身份，不接受客户端自行声明的 ID。

API 使用 `Authorization: Bearer` 请求头。缺失或错误凭据返回 JSON `401 unauthorized`；查询参数中的凭据会被拒绝，即使请求头有效。鉴权不读取请求正文。网关不会记录请求正文、凭据或 URL 日志，暂存配置也关闭了 Worker observability。

## 准入限额

`FreeGate` 使用 `new_sqlite_classes` 迁移创建 SQLite Durable Object。所有 AI 路由必须使用唯一对象名称 `global-free-tier-v1`（由 `FREE_GATE_NAME` 导出）；按客户端、模型、路由或地域拆分对象会破坏全局预算。

整个项目每个 UTC 日最多准入 **40 次 AI 尝试**，每个授权客户端在滚动 60 秒内最多 **12 次**。推理前在同一个同步 SQL 事务内检查并递增两种计数。拒绝时返回 `allowed: false` 和正整数 `retryAfterSeconds`；聊天处理器将其转换为 HTTP `429` 和 `Retry-After`。两种限额同时触发时，等待时间覆盖两者。失败或取消的尝试仍占用计数，没有退回方法。

当日计数在 00:00 UTC 重置；滚动窗口跨午夜继续有效。每次准入检查会删除过期的分钟记录，UTC 日推进时替换唯一的当日计数行。对象被回收后，持久化计数仍然有效。服务端时钟回退不会恢复已消耗额度。存储内容只有授权客户端 ID、计数和准入时间，不包含提示词、回答或聊天记录。

内部 RPC `admit(clientId, nowMs)` 必须接收鉴权所得身份和 Worker 的 `Date.now()`，不能接收请求字段或请求头中的时间。聊天处理器先验证模型、正文、格式和预算，再在实际调用 AI 前立即准入。未验收的中文合成不调用准入。

上述限额是应用请求策略，不是 Neuron 计量器。Cloudflare Free 的账户级免费额度为每天 **10,000 Neurons**，00:00 UTC 重置；同一账户内的其他应用也会消耗该额度。启用推理前必须核验实际登录的目标账户为 Workers Free。不得购买、升级、自动切换到 Paid 模型或改动其他项目 billing。目标非 Free 时继续禁用推理。参阅 [Cloudflare Workers AI 定价](https://developers.cloudflare.com/workers-ai/platform/pricing/)。

## 响应和配置

API 首先检查 secret 和 `AI`、`ASSETS`、`FREE_GATE` bindings。配置缺失或错误时，在鉴权前返回 JSON `503 gateway_unconfigured`。配置有效时先鉴权，再选择路由和方法；未知 `/v1` 路由返回 JSON `404`，推理路由的不支持方法返回 JSON `405`。API 错误不会回退到 HTML 静态资源，错误响应不缓存。

`GET /healthz` 只报告 `deployed`、`configured` 和 `inferenceEnabled`，不调用 AI 或消耗额度。配置不完整时返回 `503`，配置有效且聊天处理器可以尝试推理时报告 `inferenceEnabled: true`。它仅描述代码和配置就绪程度。健康响应不能证明账户套餐、模型访问或真实推理已经验收。其他非 API GET/HEAD 请求通过 `ASSETS` 提供资源；Worker 在 `/v1`、`/v1/*` 与 `/healthz` 之前运行，静态资源无法绕过 API 路由。不存在的产品页面以状态码 404 返回自定义错误页。

## 聊天和图片协议

鉴权后的 `GET /v1/models` 返回 OpenAI 列表，包括 `id`、`name` 和 `architecture.{input_modalities,output_modalities}`。列表包含 Qwen 文字→文字、Gemma 文字+图片→文字、Whisper 音频→文字。Whisper转写协议已实现，真实普通话识别质量仍待验收。MeloTTS 在中文输出实际验证前不公开。当前官方模型 schema：[Qwen](https://developers.cloudflare.com/workers-ai/models/qwen3-30b-a3b-fp8/)、[Gemma](https://developers.cloudflare.com/workers-ai/models/gemma-4-26b-a4b-it/)、[Whisper](https://developers.cloudflare.com/workers-ai/models/whisper-large-v3-turbo/)。

`POST /v1/chat/completions` 只接受 `@cf/qwen/qwen3-30b-a3b-fp8` 和 `@cf/google/gemma-4-26b-a4b-it`。JSON 正文包含 `model`、非空 `messages`，以及可选布尔值 `stream` 和输出预算 `max_completion_tokens` 或 `max_tokens`。默认非流式、预算为 2,048 tokens；两种预算同时存在时必须相等。Qwen 使用 `max_tokens`，Gemma 使用 `max_completion_tokens`。只有验证后的消息、预算、采样参数（`temperature`、`top_p`）、function 定义和 Gemma 流式 usage 参数会传到 binding。未知顶层字段被丢弃；非文字 response format 和非 auto tool choice 在准入前被拒绝。

正文上限 4 MiB，文字上下文（包含工具定义和参数）上限 16,000 个 JavaScript 字符，输出上限 2,048 tokens。只有 Gemma 的 user 消息可包含 `image_url`：必须为 PNG/JPEG 的 base64 data URL，服务端不抓取远程 URL。最多一张图片，解码字节≤2 MiB、宽高均为 1–1280px。服务端检查容器结构和尺寸头，客户端必须先重编码到预算内。非法模型、内容、图片或格式返回 JSON `400`，正文超限返回 `413`，均不消耗准入额度。

两种模型都接受官方支持的 function 定义。Qwen 已验证 schema 的简单属性需包含 `type` 和 `description`，不支持的 schema 关键字会被拒绝。Gemma 支持 OpenAI function 定义、assistant `tool_calls` 和携带 `tool_call_id` 的 `tool` 结果。**Qwen 的工具历史目前返回 `400`**，因为公开消息 schema 未明确其编码。两种模型的 reasoning 历史输入均在编码核验前拒绝；输出中的 reasoning 保留。Android 即使从内置模型资料获得 reasoning 能力，也只对解析后的 `https://molamaker.si` origin（默认端口或 443）省略不受支持的历史字段；其他 origin 的原有回传规则保留。需要已验证的 agent 工具往返时使用 Gemma，不自动替换模型。网关只回传 function 调用，不执行工具。Care 模式发送 `tools: []`。

非流式响应采用 `choices[].message`、completion 元数据和上游 usage。流式响应采用 SSE `choices[].delta`，保留文字、reasoning、function 调用、usage 和 stop/length/tool finish reason，最后仅发送一次 `data: [DONE]`。官方记录的 [Workers AI 原生流](https://blog.cloudflare.com/workers-ai-streaming/) 从 `response` 文字事件和明确的 `[DONE]` 转换成同一协议；普通 EOF 不代表完成。空流、非法事件、上游错误和截断流发送脱敏 SSE `error`，随后关闭，不伪造 stop 或 `[DONE]`。UTF-8 解码能保留任意字节分段中的中文。空工具数组、只有工具 ID/index 的元数据和空 reasoning 字段不算有效输出；reasoning 文字及 function 名称/参数片段必须先通过类型检查才能计入输出。

HTTP 错误保持 JSON：凭据错误 `401`，配额超限 `429` 并附 `Retry-After`，配置或模型服务不可用 `503`。已经开始流式传输后的错误使用 SSE error。客户端 abort 或 `reader.cancel()` 会取消并释放本地上游 reader，但无法保证 Cloudflare 停止已开始的推理或计费；已准入尝试不退回计数。

## 本地验证

使用 Node 22 和已提交的公共 npm registry lockfile：

```sh
npm --prefix gateway ci --registry=https://registry.npmjs.org/
npm --prefix gateway test
npm --prefix gateway run typecheck
npm --prefix gateway run dry-run
```

测试使用当前 Cloudflare Vitest 插件、真实的本地 SQLite Durable Object、运行时随机生成的测试凭据和 `remoteBindings: false`，不调用真实模型。共享协议 fixture 同时通过 Android 真实 `ProviderFactory.streamMessage` 和 `OpenAIModelsApi` 的 `GatewayProtocolTest` 消费，不使用自造客户端 parser。测试中不要启用远程 bindings。Dry-run 不提供 secret，也不部署资源；成功打包不能证明已经具备生产上线条件。

实现参考：[SQLite 事务](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/)、[SQLite 迁移](https://developers.cloudflare.com/durable-objects/best-practices/access-durable-objects-storage/)、[Worker 静态资源路由](https://developers.cloudflare.com/workers/static-assets/binding/) 和 [Cloudflare Vitest 集成](https://developers.cloudflare.com/workers/testing/vitest-integration/write-your-first-test/)。

## 有界语音协议与验收门禁

`POST /v1/audio/transcriptions` 接受 multipart 的 `file`、`model`、可选 `language`、`response_format=json`。仅允许 `@cf/openai/whisper-large-v3-turbo`。请求体不超过4 MiB；完整 RIFF/WAVE 文件不超过2 MiB，必须是16 kHz、单声道、PCM16、非空、最多30秒。服务端检查块长度、字节率和帧对齐。无效格式/重复或未知字段返回400，超体积返回413，均不计调用。`zh-CN` 或 `zh-Hans-CN` 归一为 `zh`，整份 WAV 转为base64，使用 `task=transcribe`，不会要求翻译。成功响应为 `{text,language?,duration?}`，元数据来自官方 `transcription_info`。错误 JSON、畸形输出或上游失败返回503，不伪造转写。这里已实现协议，真实普通话识别质量仍待 Task6 验收。

中文云合成与目录中的音频输出继续关闭，暂存配置明确为 `TTS_ZH_VERIFIED=false`。只有人工确认的真实中文及中英混合 MP3 均完整解码、有正时长且听验通过，并记录准确 `TTS_ZH_LANG` 后，才能同时设置该值与 `TTS_ZH_VERIFIED=true`。官方 [MeloTTS 参数](https://developers.cloudflare.com/workers-ai/models/melotts/) 只规定 `prompt` 与字符串 `lang`，没有枚举中文值或提供 voice/speed/format 控制；不能根据文档或模拟音频猜测中文参数并启用能力。

验收后，`POST /v1/audio/speech` 仅接受 `@cf/myshell-ai/melotts`、1–500个JavaScript字符的 `input`、模型ID或项目别名 `default` 的 `voice`（省略也使用default），`response_format=mp3`（默认），`speed` 省略或数值1。其他声音、格式、速度与字段明确返回400，不静默忽略。两个别名映射到已验证的模型语言，不虚构 speaker 参数。官方字节/base64输出经过有界MP3头检查后以 `audio/mpeg` 返回；头检查不代表解码或听验成功。

两个语音路由与chat共用真实的 `global-free-tier-v1` 配额对象，验证后、单次AI调用前计数，失败/取消不退回。取消后丢弃迟到的结果。Android实际 `VoiceProviderFactory` 使用现有版本化路径；阻塞HTTP的取消观察器会取消OkHttp。朗读停止/暂停会取消当前语句并使其generation失效，同时保留长期队列worker；迟到字节不能播放，取消不能触发系统回退。正常提供商错误仍沿用现有设备回退。云中文验收前，请明确选择并实际验证设备上的系统中文声音；系统朗读不是云MeloTTS，JVM测试不能证明设备已安装中文声音或实际可听。

## 后续真实音频验收（Task6）

`scripts/gateway/verify-audio.mjs` 默认不发起任何调用。live模式要求明确确认Workers Free、非私人WAV与provenance哈希、所有Task6工具共同使用的JSON ledger（`totalAttempts`、`attempts`）、环境中的项目凭据以及输出目录。每次HTTP前仅计一次，失败/取消/HTTP拒绝也保留，不重试；整个Task6合计最多15次。此本地保守计数与服务端40次/日及Neuron硬限不同。

准备好的普通话WAV由离线eSpeak生成，不是人声录音，真实ASR尚未运行。ASR阶段保存实际结果，可懂度仍需人工评审。候选中文合成必须经独立私有operator probe，使用专门非公开operator凭据，且明确确认绑定生产服务**同一个真实FreeGate namespace / script_name与全局对象**。Task6负责评审、创建/部署临时probe；本次没有添加生产公开probe路由，没有部署。probe用同一有界合成handler及operator指定的候选语言测试，生产目录/flag始终保持禁用；probe成功本身不等于生产能力验收。

候选与已验证生产阶段各发一条中文、一条中英混合请求，要求 `audio/mpeg`，保存真实文件，使用ffprobe确认codec/时长，再用ffmpeg -xerror完整解码，随后仍等待人工听验。`--stage listen --listened-chinese-and-mixed` 只为哈希吻合、已解码的这两份文件记录操作者明确的听验确认。脚本不会自动启用生产变量、创建凭据、部署或虚构听验。缺少解码工具时在发起合成前失败。候选证据、禁用生产和最终生产验收是不同阶段；获批设置准确语言和flag后还须重新验收生产。具体选项见 `node scripts/gateway/verify-audio.mjs --help`，凭据值不得放在命令参数或报告中。

## 产品站构建 {#product-site-build}

`site/` 中的双语静态产品页将当前 P0 下载与预览中的网关、浮球、关怀源码分开说明。六个下载均为临时 GitHub Actions 产物 ZIP，需要登录 GitHub，页面标示 UTC 到期时间与 ZIP 范围的 SHA-256。未签名 iOS device app/archive 不能直接安装，不是 IPA 或 TestFlight 发布。P0 构建不包含新的网关、浮球、关怀集成。

使用 Node 22：`NANODOT_DOCS_BASE=/docs/ npm --prefix website run docs:build`，然后 `node scripts/site/build.mjs`。可选的 `NANODOT_DOCS_BASE` 覆盖新根部署的文档 base，保留已有 `DOCS_BASE` 行为及默认 `/nanodot/docs/`。导出的 `buildSite({outDir, docsDir})` 将已编译文档复制到 `/docs/`，将原创离线 Dot 图形复制到专用输出，默认 `gateway/public/`。删除前核对真实文件系统路径，仓库内仅允许输出到 `gateway/public`，仓库外独立临时输出仍可用；源码树与已编译文档输入不得与输出重叠。不执行部署。运行 `node --test site/tests/*.test.mjs` 和网关测试；本地路由检查需要本地监听端口，省略 AI binding，不调用真实模型。

接入指南不接收凭据输入。隐私页说明用户主动触发的图片/音频发送到 Cloudflare、项目不持久存储 payload 与供应商自己的数据政策。已有 Cloud/服务商数据流保持独立。

## 可选客户端接入 {#optional-client-connection}

自带 key 列表新增 **Nanodot · Workers AI**（`nanodot-workers`）。`verified` 月份只表示源码与协议检查，不表示真实服务已验收。Android、iOS 和桌面的账号／自带 key 页面提供项目 key 入口，Web 控制台在「连接」页面提供入口。需要操作者发放的专用项目 key。添加连接时，原有 Cloud 登录、token、origin、头像、模型组和选中的模型均保持原状。真实验收之前，新安装默认值也保持原状。

手机等待服务商配置加载后，以独立 UUID 创建规范 origin 的 OpenAI Chat Completions 连接。重复接入沿用 UUID，不重复创建服务商。已绑定的服务商保持固定项目地址：原生服务商编辑器不能把该 UUID 的凭据移动到其他 origin。Web 切换 preset 或地址时会清除尚未保存的 key。Android 使用原有加密服务商仓库，iOS 使用原有按实例隔离的 Keychain helper，桌面使用 UUID 专属引用写入 harness 凭据存储。Python 将元数据保存为 `gateway-profile.json`，项目 key 通过 UUID 专属引用保存到原有加密保险库。客户端资源和 profile 元数据不保存明文 key。模型刷新失败保留已有模型条目。iOS、桌面和 Python 验证模型列表成功后才替换保存的 key；Android 沿用仓库原有刷新行为。

模型列表的 `architecture.input_modalities` 与 `output_modalities` 决定模型能否接收图像或音频。不能因为 Qwen 的名称就将其视为视觉模型；只有列表声明图像输入时，才将 Gemma 提供给图像功能。Whisper 属于语音模型，不进入桌面／Web 的对话模型选择器。生产门禁为 false 时，不提供中文 TTS。桌面与 Python 当前记录音频模型元数据；本次变更未新增桌面／Web 的麦克风或音频播放实现。

连接与选择模型是两个动作。手机／桌面通过原有模型设置或明确的「用于」选项选择模型。Web 控制台的「用项目 key 连接」仅保存独立连接；「使用所选网关模型」由用户明确选择已列出的对话模型。即使通过自定义表单输入该 origin，网关预览与选用也不能借用旧 slot 的 key。运行时已鉴权的 `POST /api/connections/gateway` 接收 `api_key`，随后原有的显式模型选择路由解析独立保险库引用。Python 的隐式项目 key 不会沿用到单独配置的 embedding origin；显式配置的 embedding key 保持不变。此 origin 不提供账号注册、匿名推理，也不迁移 Cloud token。

本地 JVM、Node 与 Python fixture 测试证明存储和协议行为。iOS XCTest 需要 macOS 与兼容的原生依赖。Android、桌面、Web、iOS 各自的真实模型列表与文字 SSE 验收仍待 Gateway Task 6 完成；一个平台成功不代表其他平台成功。语音 fixture 请求不代表真实 ASR 或中文 TTS 验收。
