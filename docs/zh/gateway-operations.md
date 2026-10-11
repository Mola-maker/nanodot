# 网关运维

当前产物是待源码审查的部署准备。生产 TLS、模型、真实客户端、中文音频与远端计数仍需记录实测。
保留现有 Cloud 账户、provider 与真实 origin。新的 OpenAI base 为 `https://molamaker.si/v1`，
使用独立项目凭据，不能代入旧 Cloud token。

## 待审查的资源差异

| 资源 | 精确变更 |
| --- | --- |
| Worker | 独立创建 `nanodot-gateway`，保留 `oi-wiki` 等其他 Worker |
| AI | `AI` 绑定，仅 allowlist 内 Free 模型，不切换付费服务 |
| 静态文件 | `ASSETS` → 生成的 `gateway/public`，所有请求先经过 Worker |
| SQLite DO | `FREE_GATE` → `FreeGate`，迁移 `v1` / `new_sqlite_classes`；共享对象 `global-free-tier-v1` |
| 域名 | Custom Domains `molamaker.si`、`www.molamaker.si`；www 在鉴权前301到根域；关闭 workers.dev 和 preview URLs |
| Secret | `GATEWAY_KEY_HASHES`：稳定客户端 ID → 唯一 SHA256 摘要的 JSON，仅通过私密 dashboard/stdin 输入 |
| 变量 | 初始 `INFERENCE_ENABLED=false`、`TTS_ZH_VERIFIED=false`；确认 Free 后才开启推理；中文实测前不设置 `TTS_ZH_LANG` |
| 邮件/DNS | 先保存记录 ID/type，只新增两个网页域名连接；保留全部 MX/TXT/邮件记录与已有路由 |
| 临时操作服务 | `nanodot-task6-operator` 无公开路由/workers.dev/preview URL；外部 DO 的 `script_name=nanodot-gateway`，不创建迁移 |
| 操作服务 secret | `OPERATOR_KEY_HASHES` 仅含独立 `operator-task6` 摘要；生产 `GATEWAY_KEY_HASHES` 也必须有相同身份/摘要以通过 DO 准入；`AUDIO_PROBE_LANG` 为候选精确语言变量 |
| 本地转发 | `nanodot-task6-local-relay` 永不部署；服务绑定 `OPERATOR` 远端指向操作服务，仅监听127.0.0.1 |

操作时重新核验 Workers Free。[Workers AI 官方定价](https://developers.cloudflare.com/workers-ai/platform/pricing/)
提供每天10,000 Neurons、00:00 UTC 重置；Free 超额后请求失败。应用另设所有 AI 路由共享每天40次、
每客户端滚动60秒12次，调用前原子计数；失败/取消不退款。请求次数不是 Neuron 精确预算，禁止为证明配额故意耗尽推理或升级。

## 新权限或凭据前的具体提案

使用已登录的 Dashboard 创建无敏感内容的 Hello World 占位 Worker，先关闭公开 URL，再上传已审查代码。
实际创建表单的 Access 关闭，隐含 workers.dev 地址，创建前没有关闭 URL 的控制。
点击创建前，刷新账号状态、核验 Workers Free，并检查精确名称 `nanodot-gateway`、`nanodot-task6-operator`。
若任一名称已存在，停止并审查，不覆盖或改作他用。必须获得**新的、具体的人工同意**，涵盖这两个创建动作和
短暂的 workers.dev/Version URL 公开暴露。此前的一般部署同意或已登录状态不授权这次暴露；已观察到的响应或日志不能事后消除。

**一次只创建并关闭一个 Worker**：先创建仅含已检查 Hello World 模板的 `nanodot-gateway`，立即在
Settings → Domains & Routes 关闭 workers.dev 与 Version URLs，确认两个 URL 不再提供模板且没有
Custom Domains/路由，保存实际资源/version/settings ID 和关闭证据。随后对 `nanodot-task6-operator`
重复同一流程。任一公开入口不能确认关闭就停止。在两个资源实际关闭前，不添加 AI/DO 绑定、项目摘要、
私密操作服务代码或 assets，不调用 AI。[Version URLs](https://developers.cloudflare.com/workers/versions-and-deployments/version-urls/)
启用时是公开的，未设置时继承 workers.dev 状态，可在 Dashboard 关闭；已审查配置保持
`workers_dev:false`、`preview_urls:false`。

两个 Worker 已存在并关闭后，才由人工授予**短期 Individual Workers Editor token，仅限这两个实际资源 ID**。
先审查精确角色、资源 ID 和到期时间，创建凭据及私密交接还需独立的操作时授权。不申请 Workers 产品级
Admin/Editor、zone/DNS、KV、R2、D1、AI REST 推理、用户资料、全部 zone 或无关 Worker 权限。
凭据不进入聊天、argv 或日志；初始化 token 与后续仅限网关的 CI token 分开。

在 `gateway/wrangler.jsonc` 旁准备 `gateway/.env.task6-bootstrap.jsonc`，现有 `.env.*` 规则会忽略
这个不含凭据的 JSONC 元数据文件，**唯一差异为 `routes:[]`**。另行获准的初始化从 `gateway/` 运行 Wrangler，
明确指定 `--config .env.task6-bootstrap.jsonc`，不把该文件当作 dotenv 载入；CI 继续使用标准的已审查
`wrangler.jsonc` 版本上传配置。保留已审查源码/bundle、
精确的291文件 assets manifest、AI/ASSETS、`FREE_GATE` → `FreeGate`、`v1` / `new_sqlite_classes`、
`workers_dev:false`、`preview_urls:false`、`INFERENCE_ENABLED=false`、`TTS_ZH_VERIFIED=false`，
不设置 `TTS_ZH_LANG`。通过获准的双 Worker Editor token 上传/部署到已有网关，仅应用首次迁移，
记录真实 namespace/version ID 和迁移结果。固定版本 Wrangler 的上传/迁移授权**仍未实测**：遇到精确的
权限拒绝就停止并审查该操作，不静默扩大权限。保留首次 namespace 和生产计数；不重置计数、不重复试验迁移、
不转换 schema，也不静默改为新版 exports 语法。

网关 namespace 实际存在后，才向已关闭的操作服务占位资源部署已审查的无路由代码，外部绑定
`FREE_GATE`、class `FreeGate`、`script_name=nanodot-gateway`，不设自己的迁移。添加独立项目摘要前，
核验真实 namespace 身份和公开入口仍关闭。操作服务的 AI 绑定/候选语音要求先关闭入口；此流程不授权候选调用。
项目摘要只经另行获准的私密 stdin/Dashboard 输入。保存 DNS/邮件/路由 ID、重新核验 Free 并取得公开暴露的
具体同意后，只通过已登录 Dashboard 添加根域与 www Custom Domains；保留全部邮件记录和无关 Worker，
域名操作不使用无 zone 权限的 token。

后续 CI 存储 secret 前，核验受保护的 `gateway-production` environment：required human reviewer 为
`Mola-maker`（GitHub 用户 ID `229602071`），`prevent_self_review=false`，自定义部署分支仅允许 `main`，
且 `can_admins_bypass=false`。所有者允许发起人审批；每次仍须由 Mola-maker 本人实际批准，代理不得提交审批。
仅存储 `CLOUDFLARE_API_TOKEN` secret 和 `CLOUDFLARE_ACCOUNT_ID` environment variable。
使用**独立的短期 Individual Worker Editor token，仅限已存在的 `nanodot-gateway`**。CI 只上传/激活版本，
不得创建 Worker、迁移存储、修改域名/路由、设置 secret 或部署操作服务。

[官方 Workers 权限](https://developers.cloudflare.com/workers/authorization/workers/)要求创建使用已有账号权限，
Editor 部署已有 Worker。Custom Domains 支持**单 Worker Editor 加每个受影响 zone 的 Workers Routes Write**。
本提案仍由已登录 Dashboard 连接域名，避免向初始化/CI token 增加 zone 权限。Editor 本身可修改更多内容，
包括 Worker settings/secret 和通过 Data Studio 访问 Durable Object 数据；CI 的“只上传”是人工审批下的
操作约束，不是更窄的固有 token 权限。[DO 权限](https://developers.cloudflare.com/workers/authorization/durable-objects/)
继承实现它的 Worker，绑定资源部署无需该资源的独立直接权限。域名/settings/上传失败不证明需要账号级 Admin，
应先记录并审查被拒绝的精确操作。此前被自动审批拒绝的账号级 Admin 准备动作，不授权改用 API/CLI 重做。
Remote service/edge-preview 传输权限仍是下文所述的独立未解决门槛；不假定双 Worker Editor 覆盖尚不存在的
relay 脚本或账号会话 API，不启动默认 OAuth，也不新增 Admin 授权。

## 源码检查与首次部署

```sh
npm ci --prefix gateway
npm ci --prefix website
node --check scripts/gateway/verify-live.mjs
node --test scripts/gateway/*.test.mjs
npm --prefix gateway run typecheck
npm --prefix gateway test
node --test site/tests/*.test.mjs
NANODOT_DOCS_BASE=/docs/ npm --prefix website run docs:build
node scripts/site/build.mjs
node scripts/gateway/verify-live.mjs --scan-site gateway/public --report evidence/task-6/static-scan.json
export WRANGLER_LOG_PATH="$PWD/evidence/task-6/wrangler-logs/"
mkdir -p "$WRANGLER_LOG_PATH"
npm --prefix gateway run dry-run
cd gateway
npx wrangler deploy --config wrangler.operator.jsonc --dry-run --outdir dist-operator
npx wrangler deploy --config wrangler.operator-relay.jsonc --dry-run --outdir dist-relay
```

Wrangler 编译日志写入忽略且可写的 `evidence/task-6/wrangler-logs/`，而非可能只读的默认 home 目录。
此设置用于下一次本就需要执行的 dry-run；不为隐藏已存错误重复运行。

需要 Node22。gateway 测试池 `remoteBindings:false`，CI 不调用模型。旧 GitHub Pages 原始源码发布路径已关闭。
默认文档构建保留旧 base，仅网关使用 `/docs/`。生成产物/证据忽略。Phase B 前保存 commit、产物哈希、
账号 Free 核验、现有路由/域名 ID 和全部 DNS 记录 type/ID。按上文具体获准的占位资源创建/关闭、
已有 Worker Editor 上传/迁移和 Dashboard 域名流程执行。仅通过另行获准的私密 stdin/Dashboard 设置摘要 secret，
绝不写入 argv、聊天、日志。初始关闭推理，先验 TLS、health、
鉴权、preflight、404，再单独审查 `INFERENCE_ENABLED=true` 配置变更，TTS 仍为 false。CI 不负责初始化。

## 无 AI 与有限真实验收

关闭 shell tracing，从私密本地环境读取 `NANODOT_TEST_GATEWAY_KEY`，不提交、不放进 argv。
无 AI 命令不需要调用次数 ledger：

```sh
node scripts/gateway/verify-live.mjs --base https://molamaker.si --report evidence/task-6/no-ai.json
```

检查 health 配置、未鉴权/已鉴权 models、API JSON/no-store404、未知网页 no-store404、根域/www 精确 CORS 及必需的 methods/headers、
preflight、外域拒绝、网页/静态文件和不带 Bearer 的 www301。所有鉴权重定向都拒绝，包括同 host，
不会转发 Bearer。响应检查只搜索实际测试 key；静态构建检查另行进行，builder 仅复制固定审查列表。
缺少生产 secret 返回503，不能冒充401通过。

整个任务只初始化**一份**忽略且 mode0600 的 ledger：`{ "totalAttempts": 0, "attempts": [] }`。
所有客户端、探针、取消、失败、重试均在实际 dispatch 前保留次数，最多15、不重置/退款。
`text`、`vision`、`care`、`cancel` 阶段必须显式带 `--live-ai --free-plan-confirmed --ledger PATH`。
视觉还需非私密本地 PNG、`--image-sha256`，不抓取远端图片。记录响应哈希、状态和脱敏请求 ID；
取消阶段必须观察到跨事件循环仍未完成的 reader read，再触发本地 abort/cancel，并确认该 read 关闭或 AbortError。
空流或在此之前自然结束的流验收失败；本地取消不能证明上游 AI 已物理停止。
不记录凭据/提示词。自动检查中文 SSE 与无 tools 结构，图片标记语义、关怀 proposal 和原生取消仍需人工/客户端观察。

预分配：Android文字1、desktop1、Web1、iOS1、视觉1、ASR1、私密中文/混合TTS2、条件生产TTS2、
关怀1、取消1，共12，剩3次供经过审查的失败/重试。无AI检查0；候选TTS失败则跳过生产TTS。
先协调同 commit 的真实客户端，再消耗次数，Android优先。原生加密存储、浮层、真实 simulator XCTest 单独验收。
未签名 iOS device archive 不等于 simulator 测试。

## 私密 TTS 与隔离远端计数

[DO environments](https://developers.cloudflare.com/durable-objects/reference/environments/)说明外部 script_name
可访问同一对象以及 SQLite 远端开发限制，不能用 preview SQLite 冒充生产 namespace。
采用部署后无路由的私密操作 Worker，通过[官方远端服务绑定模式](https://developers.cloudflare.com/workers/local-development/#using-remote-resources-with-durable-objects-and-workflows)
让本地 relay 访问它。已安装 Wrangler schema 支持 `services[].remote:true`。
relay 没有 AI/DO 绑定，仅转发 POST speech/proof；操作 Worker 绑定生产 namespace，候选音频在调用 AI
之前准入精确 `global-free-tier-v1`。已安装 Wrangler 还会通过账号 subdomain/script 的 `edge-preview` API 创建名为
`nanodot-task6-local-relay` 的鉴权传输 preview session，上传 Wrangler proxy，
不是 preview SQLite namespace 或公开部署 relay。会话建立属于独立的操作时控制动作，
实际鉴权/权限范围届时审查；不能假定生产 CI token 足够、创建持久宽权限 token，
或发布会话 URL/preview token。本地编译/测试不证明真实远端连接；远端连接仍未验证。

批准并完成凭据配置后，从仓库根目录启动：

```sh
node scripts/gateway/start-operator-relay.mjs --free-plan-confirmed --session-controls-reviewed
```

launcher 必须读取私密 `CLOUDFLARE_API_TOKEN`/`CLOUDFLARE_ACCOUNT_ID` env，
先 `GET /accounts/<id>/workers/subdomain`，不存在则不启动，不输出subdomain/token，
固定 loopback 和 Wrangler warn 日志。之后 Wrangler 使用
`GET /accounts/<id>/workers/subdomain/edge-preview` 与
`POST /accounts/<id>/workers/scripts/nanodot-task6-local-relay/edge-preview`。
已安装 Wrangler 的 OAuth `workers_scripts:write` 描述覆盖 script/DO/subdomain/trigger/tail，
宽于单Worker CI。本阶段不授权新 OAuth login/default scope 或传输token；实际可用账号权限仍是操作时 gate。
不能绕过 launcher，否则 Wrangler 会在缺少subdomain时自动PUT注册随机值。
这里不能加 `--local`，它会禁用显式远端服务；永不部署 relay。
`verify-audio.mjs` candidate 使用 `--probe-base http://127.0.0.1:8788/v1`、
`--probe-local-relay-confirmed --probe-private-confirmed --probe-shared-gate-confirmed`、精确
`--candidate-lang` 和共享 ledger。本地 env 中保存 `AUDIO_PROBE_CREDENTIAL`。
两个真实 MP3 都需 ffmpeg 完整解码、时长检查、SHA256，再由人听这两份精确中文/混合音频；
`--stage listen --listened-chinese-and-mixed` 将确认与哈希绑定。只有后续审查才设置生产精确
`TTS_ZH_LANG` 和 `TTS_ZH_VERIFIED=true`，之后另测生产TTS。

私密 `/quota-proof` 使用同一生产 namespace/class，但对象名为 **`task6-isolated-quota-v1`**，
13次服务端时间准入，不推理。部署后可证明真实 SQLite 滚动计数拒绝；永不修改/预置
`global-free-tier-v1`。重复运行可能少于12次通过，应如实记录。此 HTTP429 是隔离操作响应，**不是生产HTTP429**。
已有 workerd SQLite 测试本地证明 daily40/并发/持久化，注入配额拒绝测试本地证明 HTTP pipeline。
实际生产429只有在<=15次合法请求中自然遇到才算实测，供应商 Neuron 耗尽也只有自然遇到才记录。
无公开 reset endpoint，不预置生产计数到40。获准后、relay 已运行时，无 AI 远端计数命令为：

```sh
node --input-type=module <<'JS'
import { writeFile } from 'node:fs/promises';
const key = process.env.AUDIO_PROBE_CREDENTIAL;
if (!key) throw Error('Dedicated operator credential missing');
const response = await fetch('http://127.0.0.1:8788/quota-proof', {
  method: 'POST', headers: { Authorization: `Bearer ${key}` },
  redirect: 'error', signal: AbortSignal.timeout(30000),
});
const proof = await response.json();
if (response.status !== 429 || proof.scope !== 'isolated-object-not-production-http429'
    || proof.objectName !== 'task6-isolated-quota-v1') throw Error('Counter proof failed');
await writeFile('evidence/task-6/isolated-counter.json', JSON.stringify({
  scope: proof.scope, objectName: proof.objectName, httpStatus: response.status,
  admitted: proof.admitted, denied: proof.denied, retryAfterSeconds: proof.retryAfterSeconds,
  actualProductionHttp429: 'unverified',
}, null, 2), { mode: 0o600 });
JS
```

已安装代码的会话 teardown 清理刷新 timer、abort 请求、关闭tail WebSocket、dispose本地workerd，
这条路径没有DELETE控制API，不能声称停止本地进程立即吊销服务端preview token。
记录teardown后，通过审查的dashboard控制撤销独立操作凭据并仅移除临时Worker，保留生产DO存储/邮件。
本阶段没有执行清理。完成后移除生产摘要里的临时操作身份，停止relay，记录版本/绑定后
只禁用/删除临时操作服务。

## 证据与回滚

忽略目录 `evidence/task-6/` 保存脱敏 commit/产物SHA、Worker/version/namespace/domain ID、前后网页DNS/
路由差异、TLS时间、共享ledger、各能力PASS/FAIL/未验证、四客户端、音频哈希/人工听验/精确语言。
记录邮件相等性而不公开邮箱值；CLI whoami 仅汇总 readiness，不保存个人输出。

已有部署在 `gateway/` 记录先前版本后激活：

```sh
npx wrangler deployments list
npx wrangler versions list
npx wrangler rollback PRIOR_VERSION_ID --message "Restore reviewed gateway version"
```

版本回滚不撤销 DO 存储/迁移和域名，保留 `v1`/namespace，不删除生产计数。首次部署无旧版本，
部署同源码但 `INFERENCE_ENABLED=false`、`TTS_ZH_VERIFIED=false`，保留网页/models和明确503恢复提示。
如需恢复域名只按已记录 ID 恢复本次新增网页连接，不删除邮件/其他 Worker。客户端保留先前配置，可选择已有
其他 provider。真实验收前不切换新安装默认值，此工作流不自动发布 Release/TestFlight。
