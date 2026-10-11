# 私有 Workers AI 网关

`gateway/` 提供私有鉴权和持久化免费额度准入计数。目前推理尚未启用：通过鉴权的聊天、转写和语音合成预留路由返回 `503 inference_unavailable`。这些请求不会调用模型，也不会消耗准入额度。空的静态资源暂存目录不代表网站已完成。此项工作不更改已有 App 连接或 nanoMuse Cloud。

预定 origin 为 `https://molamaker.si`，OpenAI base URL 为 `https://molamaker.si/v1`。这只是预定部署地址，不表示已验收线上服务。已有 Cloud 和第三方服务商保留真实 origin。

## 项目凭据

操作者向获授权的客户端提供独立项目凭据。此网关不创建账户、注册、Hub 或会话同步。Cloudflare 控制 token 只能由部署环境持有，不可当作项目凭据输入客户端。

Worker secret `GATEWAY_KEY_HASHES` 是一个 JSON 对象，将稳定且非空的客户端 ID 映射到项目凭据的 64 位十六进制 SHA-256 摘要。原始凭据只能保存在客户端已有加密存储中。禁止公开共享 key、编入 APK 或网页，或写入源码、日志和文档。重复摘要、格式错误和空配置都会拒绝服务。为不同客户端分配不同凭据；服务端通过匹配摘要确定身份，不接受客户端自行声明的 ID。

API 使用 `Authorization: Bearer` 请求头。缺失或错误凭据返回 JSON `401 unauthorized`；查询参数中的凭据会被拒绝，即使请求头有效。鉴权不读取请求正文。网关不会记录请求正文、凭据或 URL 日志，暂存配置也关闭了 Worker observability。

## 准入限额

`FreeGate` 使用 `new_sqlite_classes` 迁移创建 SQLite Durable Object。后续所有 AI 路由必须使用唯一对象名称 `global-free-tier-v1`（由 `FREE_GATE_NAME` 导出）；按客户端、模型、路由或地域拆分对象会破坏全局预算。

整个项目每个 UTC 日最多准入 **40 次 AI 尝试**，每个授权客户端在滚动 60 秒内最多 **12 次**。推理前在同一个同步 SQL 事务内检查并递增两种计数。拒绝时返回 `allowed: false` 和正整数 `retryAfterSeconds`；推理处理器需将其转换为 HTTP `429` 和 `Retry-After`。两种限额同时触发时，等待时间覆盖两者。失败或取消的尝试仍占用计数，没有退回方法。

当日计数在 00:00 UTC 重置；滚动窗口跨午夜继续有效。每次准入检查会删除过期的分钟记录，UTC 日推进时替换唯一的当日计数行。对象被回收后，持久化计数仍然有效。服务端时钟回退不会恢复已消耗额度。存储内容只有授权客户端 ID、计数和准入时间，不包含提示词、回答或聊天记录。

内部 RPC `admit(clientId, nowMs)` 必须接收鉴权所得身份和 Worker 的 `Date.now()`，不能接收请求字段或请求头中的时间。下一阶段推理实现将在模型和正文验证后、实际调用 AI 前立即准入。目前不可用的路由不调用准入。

上述限额是应用请求策略，不是 Neuron 计量器。Cloudflare Free 的账户级免费额度为每天 **10,000 Neurons**，00:00 UTC 重置；同一账户内的其他应用也会消耗该额度。启用推理前必须核验实际登录的目标账户为 Workers Free。不得购买、升级、自动切换到 Paid 模型或改动其他项目 billing。目标非 Free 时继续禁用推理。参阅 [Cloudflare Workers AI 定价](https://developers.cloudflare.com/workers-ai/platform/pricing/)。

## 响应和配置

API 首先检查 secret 和 `AI`、`ASSETS`、`FREE_GATE` bindings。配置缺失或错误时，在鉴权前返回 JSON `503 gateway_unconfigured`。配置有效时先鉴权，再选择路由和方法；未知 `/v1` 路由返回 JSON `404`，预留推理路由的不支持方法返回 JSON `405`。API 错误不会回退到 HTML 静态资源，错误响应不缓存。

`GET /healthz` 只报告 `deployed`、`configured` 和 `inferenceEnabled`，不调用 AI 或消耗额度。配置不完整时返回 `503`，本阶段始终报告 `inferenceEnabled: false`。健康响应不能证明账户套餐、模型访问或真实推理已经验收。其他非 API GET/HEAD 请求通过 `ASSETS` 提供资源；Worker 先运行，静态资源无法绕过 API 路由。

## 本地验证

使用 Node 22 和已提交的公共 npm registry lockfile：

```sh
npm --prefix gateway ci --registry=https://registry.npmjs.org/
npm --prefix gateway test
npm --prefix gateway run typecheck
npm --prefix gateway run dry-run
```

测试使用当前 Cloudflare Vitest 插件、真实的本地 SQLite Durable Object、运行时随机生成的测试凭据和 `remoteBindings: false`，不调用真实模型。测试中不要启用远程 bindings。Dry-run 不提供 secret，也不部署资源；成功打包不能证明已经具备生产上线条件。

实现参考：[SQLite 事务](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/)、[SQLite 迁移](https://developers.cloudflare.com/durable-objects/best-practices/access-durable-objects-storage/)、[Worker 静态资源路由](https://developers.cloudflare.com/workers/static-assets/binding/) 和 [Cloudflare Vitest 集成](https://developers.cloudflare.com/workers/testing/vitest-integration/write-your-first-test/)。
