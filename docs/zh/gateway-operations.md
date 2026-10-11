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

首次 Worker 创建、SQLite 迁移、secret 与两个域名使用已登录 dashboard，在审查资源差异后执行。
不能为了初始化创建持久宽权限 token。真实 namespace/version/domain ID 在创建后写入忽略的证据，不提前编造。

后续 CI 的 GitHub `gateway-production` environment 必须先配置人工 required reviewers、禁止自己批准、
部署分支仅 `main`，然后才存储 `CLOUDFLARE_API_TOKEN` secret 和 `CLOUDFLARE_ACCOUNT_ID` environment variable。
使用短期 account API token，仅对已存在的 `nanodot-gateway` 授予 **Editor**。CI 仅上传/激活版本，
不创建 Worker、不改域名/路由、不迁移存储、不设置项目 secret、不部署操作服务。

[官方 Workers 权限](https://developers.cloudflare.com/workers/authorization/workers/)要求创建 Worker 使用产品级
Admin，部署已有 Worker 使用 Editor；改域名还需该 zone 的 Workers Routes Write，且 Custom Domain
目前不支持单 Worker 角色。因此首次域名操作由现有 dashboard 完成，不产生持久宽权限 token。
[DO 权限](https://developers.cloudflare.com/workers/authorization/durable-objects/)继承实现它的 Worker。
绑定资源部署不需要该资源的直接数据权限。例行版本 CI 不申请 DNS Edit、KV、R2、D1、AI REST 推理、
用户资料或全部 zone 权限。若 Wrangler 实际拒绝精确权限，停止该动作并记录原因，审查后才请求具体增量权限。

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
npm --prefix gateway run dry-run
cd gateway
npx wrangler deploy --config wrangler.operator.jsonc --dry-run --outdir dist-operator
npx wrangler deploy --config wrangler.operator-relay.jsonc --dry-run --outdir dist-relay
```

需要 Node22。gateway 测试池 `remoteBindings:false`，CI 不调用模型。旧 GitHub Pages 原始源码发布路径已关闭。
默认文档构建保留旧 base，仅网关使用 `/docs/`。生成产物/证据忽略。Phase B 前保存 commit、产物哈希、
账号 Free 核验、现有路由/域名 ID 和全部 DNS 记录 type/ID。获准后部署独立 Worker、迁移和 assets，
通过私密 stdin/dashboard 设置摘要 secret；绝不写入 argv、聊天、日志。初始关闭推理，先验 TLS、health、
鉴权、preflight、404，再单独审查 `INFERENCE_ENABLED=true` 配置变更，TTS 仍为 false。CI 不负责初始化。

## 无 AI 与有限真实验收

关闭 shell tracing，从私密本地环境读取 `NANODOT_TEST_GATEWAY_KEY`，不提交、不放进 argv。
无 AI 命令不需要调用次数 ledger：

```sh
node scripts/gateway/verify-live.mjs --base https://molamaker.si --report evidence/task-6/no-ai.json
```

检查 health 配置、未鉴权/已鉴权 models、API JSON/no-store404、未知网页404、根域/www 精确 CORS、
preflight、外域拒绝、网页/静态文件和不带 Bearer 的 www301。所有鉴权重定向都拒绝，包括同 host，
不会转发 Bearer。响应检查只搜索实际测试 key；静态构建检查另行进行，builder 仅复制固定审查列表。
缺少生产 secret 返回503，不能冒充401通过。

整个任务只初始化**一份**忽略且 mode0600 的 ledger：`{ "totalAttempts": 0, "attempts": [] }`。
所有客户端、探针、取消、失败、重试均在实际 dispatch 前保留次数，最多15、不重置/退款。
`text`、`vision`、`care`、`cancel` 阶段必须显式带 `--live-ai --free-plan-confirmed --ledger PATH`。
视觉还需非私密本地 PNG、`--image-sha256`，不抓取远端图片。记录响应哈希、状态和脱敏请求 ID；
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
