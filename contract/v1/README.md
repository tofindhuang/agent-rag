# Java↔gateway 契约 v1

状态：Frozen（2026-10-23）
适用范围：Java 业务层 ↔ gateway。
规范性：本文档是契约的唯一权威来源。spec 与 ADR 只记录设计取舍，细节冲突以本文档为准。

## 1. 版本

- 当前主版本：`1`。
- `GET /health` 返回 `contractVersion: "1"`；`run_started` 再次携带同一版本。
- Java 启动时校验主版本，不匹配必须拒绝启动。
- 纯增量变更（新增事件、新增可选字段）不升主版本；删除字段、改变字段语义、改变终态规则必须升主版本。

## 2. 传输、鉴权与身份

- Java → gateway：HTTP/JSON；prompt 响应为 `text/event-stream`（SSE）。
- gateway → Java / 沙箱：HTTP/JSON；`run_code` 的执行回传为 SSE。
- Java 与 gateway 之间使用共享 Bearer token：`Authorization: Bearer <GATEWAY_API_TOKEN>`；开发环境可为空。
- 会话类请求必须携带 `X-User-Id`；gateway 校验它与会话绑定一致。
- `X-Request-Id` 由 Java 在发起 prompt 时生成，gateway 透传到工具回调与日志；缺失时 gateway 生成。
- 错误响应统一 envelope：

```json
{ "error": { "code": "SESSION_NOT_FOUND", "message": "...", "retryable": false } }
```

- `message` 面向用户/模型可读，不含堆栈、SQL、内部地址；诊断细节只进日志。

## 3. 会话生命周期

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| `GET` | `/health` | — | `200 { ok: true, contractVersion: "1", sessions: <number> }` |
| `PUT` | `/sessions/:id` | `X-User-Id`，可选 body `{ model }` | `200 { sessionId, created, restored, model? }` |
| `POST` | `/sessions/:id/prompt` | `X-User-Id`，`{ text, turnId }` | `200` SSE |
| `POST` | `/sessions/:id/abort` | `X-User-Id`，空 body | `202` |
| `DELETE` | `/sessions/:id` | `X-User-Id` | `204` |
| `DELETE` | `/sessions/:id/files` | `X-User-Id` | `204` |

规则：

- **ID 由 Java 生成**，gateway 不生成业务会话 ID。
- `PUT` 幂等：不存在则创建（`created: true`）；已存在且归属相同返回 `created: false`；归属不同返回 `409 SESSION_CONFLICT`。
- `restored` 表示本次调用是否从持久化的 pi session 恢复；已驻留内存的会话重试返回 `false`。
- `model` 可选，由 Java 按用户选择的供应商模型下发：`{ providerId?, name?, api, baseUrl, apiKey, modelId, displayName?, contextWindow?, reasoning?, vision? }`。gateway 为该会话构建隔离的模型运行时，密钥仅存内存、不进 session 文件；响应回显实际模型 `{ provider, id, name }`。未携带时使用部署默认模型；baseUrl 与探测走同一出站策略（默认公网 HTTPS，元数据地址永远拒绝），被策略拒绝时返回 `400 INVALID_REQUEST`。
- `prompt` 的 `turnId` 由 Java 生成，必须非空；`run_started` 与 `run_settled` 回显该值。
- **一个会话同时只允许一个 Run**：已有进行中的 Run 时，第二个 prompt 在写 SSE 头之前返回 `409 RUN_IN_PROGRESS`，不排队。
- 显式 `abort` 返回 `202`；当前 Run 以 `run_settled { status: "aborted" }` 收尾；空闲时返回 `409 NO_ACTIVE_RUN`。
- 客户端断连仍触发 abort（ADR-0002），作为显式中断的兜底。
- `DELETE` 幂等：不存在也返回 `204`；存在但归属不符返回 `403 USER_MISMATCH`。
- `DELETE /sessions/:id/files` 用于用户主动彻底删除（ADR-0011）：释放内存 AgentSession（如有），删除 pi JSONL 与 sidecar；幂等（文件不存在也返回 `204`）；归属以侧车 `X-User-Id` 为准，不符返回 `403 USER_MISMATCH`；文件存在但侧车缺失时归属不可证明，同样拒绝（`403`）。普通归档只用 `DELETE /sessions/:id`，不删文件。
- 所有会话操作的 `X-User-Id` 与会话归属不一致时返回 `403 USER_MISMATCH`；会话不存在返回 `404 SESSION_NOT_FOUND`（`DELETE` 除外）。

## 4. SSE 帧与连接语义

```
id: <runId>:<seq>
event: <领域事件名>
data: <单行紧凑 JSON>

```

- `seq` 每轮从 `1` 单调递增；`run_started` 为 `1`，`run_settled` 为最后一帧。
- `id` 由 `runId` 与 `seq` 派生；**不支持回放**，忽略 `Last-Event-ID`。
- 心跳：每 15 秒发送 `: ping` 注释帧，不占 `seq`、不产生事件。
- 响应头：`content-type: text/event-stream; charset=utf-8`、`cache-control: no-cache, no-transform`、`x-accel-buffering: no`。
- HTTP 200 写出后，第一帧必须是 `run_started`；会话不存在、归属不符、已有 Run 等错误在写 SSE 头之前返回 JSON 错误。
- `run_settled` 必须出现且只出现一次；发出后立即关闭流，不再发送任何帧。
- 一个 prompt 请求对应一个 Run；不在一连接上复用多个 Run。

## 5. 领域事件

| 事件 | data |
|---|---|
| `run_started` | `{ runId, turnId, sessionId, contractVersion }` |
| `thinking_delta` | `{ delta }` |
| `text_delta` | `{ delta }` |
| `tool_call_delta` | `{ toolCallId, name, delta }` |
| `tool_call` | `{ toolCallId, name, args }` |
| `tool_progress` | `{ toolCallId, name, kind, phase?, chunk? }` |
| `tool_result` | `{ toolCallId, name, isError, durationMs, content, truncated, details? }` |
| `message_end` | `{ seqInTurn, role: "assistant", text, thinking, toolCalls, usage, stopReason }` |
| `compaction` | `{ reason, tokensBefore, tokensAfter }` |
| `retry` | `{ attempt, maxAttempts, delayMs, message }` |
| `run_settled` | `{ status, turnId, error? }` |

字段规则：

- `tool_progress.kind`：`phase`、`stdout`、`stderr`。`phase` 时必须有 `phase` 字段且无 `chunk`；`stdout`/`stderr` 时必须有 `chunk`，单块 ≤ 2048 UTF-8 字节，按字符边界切分。
- `tool_result.content`：文本结果，按 64 KiB（65536 UTF-8 字节）截断；被截断时 `truncated: true`。模型看到的内容与这里一致。
- `tool_result.details`：可选的结构化工具结果，不进入模型上下文，只供 UI/消费者展示（当前 `kb_search` 放 `{ count, passages }`）。序列化后超过 8 KiB 时 gateway 省略该字段；消费者必须容忍缺失（旧 gateway 不发送）。
- `message_end`：
  - `seqInTurn` 每轮从 1 开始，每一条 assistant 消息递增；工具循环会产生多条。
  - `text` 为所有 `text` 内容块拼接；`thinking` 为所有 `thinking` 内容块拼接；没有则为空字符串。
  - `toolCalls` 为 `[{ toolCallId, name, args }]`，没有则为空数组。
  - `usage` 字段：`input`、`output`、`cacheRead`、`cacheWrite`、`reasoning`（可选）、`totalTokens`、`cost { input, output, cacheRead, cacheWrite, total }`。
  - `stopReason`：`pending`、`stop`、`length`、`toolUse`、`error`、`aborted`、`deferred`。
- `compaction.tokensAfter` 可为 `null`（provider 未给估算值时）。
- `run_settled.status`：`completed`、`aborted`、`error`。`error` 时必须有 `{ code, message, retryable }`。
- `tool_progress` 只用于展示，不进入模型上下文；模型只认 `tool_result`。
- `kb_search` 不产生阶段假进度；`run_code` 在流式执行落地后产生进度。

## 6. pi 事件映射与派生规则

| pi 事件 | 领域事件 / 处理 |
|---|---|
| `agent_start` | `run_started`（`seq = 1`） |
| `message_update` + `thinking_delta` | `thinking_delta` |
| `message_update` + `text_delta` | `text_delta` |
| `message_update` + `toolcall_delta` | `tool_call_delta`；从 `partial.content[contentIndex]` 取 `id`/`name`，缺失则跳过 |
| `message_update` + `toolcall_end` | `tool_call`；取 `toolCall.id`、`toolCall.name`、`toolCall.arguments` |
| `message_end`（assistant） | `message_end`；非 assistant 消息忽略 |
| `tool_execution_update` 且 `details.progress` 合法 | `tool_progress`；其余忽略 |
| `tool_execution_end` | `tool_result`；拼接 `result.content` 中的文本块并截断 |
| `compaction_end` | `compaction`；`tokensAfter = result.estimatedTokensAfter ?? null` |
| `auto_retry_start` | `retry`；`message = errorMessage` |
| `agent_settled` | `run_settled`；见下 |
| `agent_end`（含 `willRetry`） | 不产生事件；`willRetry: true` 表示后面还有重试，不能当终态 |
| 其他事件 | 忽略（`turn_*`、`message_start`、`tool_execution_start`、`queue_update`、`session_info_changed`、`thinking_level_changed`、`auto_retry_end`、`compaction_start`、`summarization_*`、`bash_execution_update`） |

`run_settled` 派生：

1. `agent_settled.aborted === true` → `status: "aborted"`。
2. 否则，若本轮出现过 assistant 消息 `stopReason === "error"` → `status: "error"`，`error.code = "MODEL_ERROR"`，`error.message` 取最后一条 assistant 消息的 `errorMessage`（缺失时为 `"model call failed"`），`retryable: true`。
3. 否则 → `status: "completed"`。

工具进度约定：

- 工具通过 `onUpdate` 回传 `partialResult.details.progress = { kind, phase?, chunk? }`。
- gateway 只转发符合该形状的进度；`toolCallId`、`name` 取自 `tool_execution_update` 事件本身。
- 进度不是工具结果，不参与审计与模型上下文。

## 7. 错误模型

HTTP 错误码：

| code | HTTP | retryable | 场景 |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | false | 参数缺失或格式错误 |
| `UNAUTHORIZED` | 401 | false | 共享 token 不正确 |
| `USER_MISMATCH` | 403 | false | 身份与会话归属不符 |
| `SESSION_NOT_FOUND` | 404 | false | 会话不存在 |
| `SESSION_CONFLICT` | 409 | false | PUT 时会话归属不同 |
| `RUN_IN_PROGRESS` | 409 | false | 同一会话已有进行中的 Run |
| `NO_ACTIVE_RUN` | 409 | false | 空闲时调用 abort |
| `UPSTREAM_UNAVAILABLE` | 503 | true | gateway 无法连接业务层或沙箱 |
| `SANDBOX_UNAVAILABLE` | 503 | true | 沙箱 Docker daemon 或镜像不可用 |
| `SANDBOX_QUEUE_TIMEOUT` | 503 | true | 沙箱执行排队超过上限 |
| `SANDBOX_CODE_TOO_LARGE` | 413 | false | 提交的代码超过大小上限 |
| `RATE_LIMITED` | 429 | true | 请求或 token 超过限流窗口 |
| `PROVIDER_UNREACHABLE` | 502 | true | 供应商端点不可达（DNS/连接/超时） |
| `PROVIDER_AUTH_FAILED` | 502 | false | 供应商拒绝 API Key（401/403） |
| `PROVIDER_PROBE_FAILED` | 502 | false | 供应商返回非 2xx 或无法解析模型列表 |
| `INTERNAL` | 500 | false | 未分类错误 |

Run 终态错误码（`run_settled.error.code`）：

| code | retryable | 场景 |
|---|---|---|
| `MODEL_ERROR` | true | 模型/供应商调用失败 |
| `INTERNAL` | false | 引擎内部失败 |

消费者合成错误（不在 gateway 输出中出现）：

| code | 场景 |
|---|---|
| `STREAM_INTERRUPTED` | Java 读取循环结束但未收到 `run_settled`；把已累积文本落成 `partial` |

流式错误不另发 `error` 事件，一律由 `run_settled.error` 收尾。

## 8. 工具回调协议

gateway 调用 Java 或沙箱时注入：

```
Authorization: Bearer <GATEWAY_API_TOKEN>
X-User-Id: <userId>
X-Session-Id: <sessionId>
X-Turn-Id: <turnId>
X-Run-Id: <runId>
X-Tool-Call-Id: <toolCallId>
X-Request-Id: <requestId>
```

- 身份只从 header 读取，**绝不从工具参数读取**；模型无法伪造用户身份。
- 非 2xx 使用第 2 节的错误 envelope；gateway 转成 `isError: true` 的 `tool_result`，保留 `code` 供日志。

### `kb_search`

请求：

```json
{ "query": "...", "topK": 5 }
```

响应：

```json
{
  "passages": [
    {
      "passageId": "...",
      "documentId": "...",
      "seq": 3,
      "page": 12,
      "title": "...",
      "content": "...",
      "score": 0.82,
      "reranked": true,
      "kbId": "...",
      "kbName": "工作资料"
    }
  ]
}
```

- `topK` 默认 5、上限 10；超出上限按 10 处理。
- `page` 可选（PDF 才有）；`passageId` 是同一切片在 Qdrant 中的稳定点 id。
- `kbId`/`kbName` 是引用来源的知识库快照；旧 gateway/旧数据可能缺失，消费者需容忍。
- 空结果返回 `{ "passages": [] }`；索引不可用、未配置或超时返回错误 envelope，不降级为空。

### `run_code`（M1 流式）

请求：`POST /api/exec`，body `{ language, code, timeoutMs }`，响应为 SSE：

```
started { execId }
phase   { phase }
stdout  { chunk }
stderr  { chunk }
result  { exitCode, durationMs, timedOut, truncated }
```

- 任何上游断连都杀容器，并写一条 `status=aborted` 的执行记录。
- `stdout`/`stderr` 单块 ≤ 2 KiB，总量仍受 64 KiB 上限约束。
- `result` 必须出现且唯一；之后关闭流。
- 流开始前的错误使用 §7 envelope；排队发生在流开始之前，流内不发送 `queued` phase。
- 沙箱错误码（纯增量，不升主版本）：`SANDBOX_UNAVAILABLE`、`SANDBOX_QUEUE_TIMEOUT`、`SANDBOX_CODE_TOO_LARGE`。
- 限流错误码（纯增量）：`RATE_LIMITED`（429），响应带 `Retry-After: <秒>`；工具回调收到时由 gateway 转成 `isError: true` 的 `tool_result`。

## 9. 模型供应商探测（内部）

Java 在用户保存供应商前调用，用于校验凭证并获取可选模型。

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| `POST` | `/providers/probe` | `Authorization: Bearer <token>`，`{ api, baseUrl, apiKey }` | `200 { models: [{ id, name }] }` |

规则：

- `api`：`openai-completions` 或 `anthropic-messages`。
- Base URL 归一化：去掉尾部斜杠与 query/fragment；`openai-completions` 请求 `{baseUrl}/models`；`anthropic-messages` 请求 `{baseUrl}/v1/models`（`baseUrl` 已以 `/v1` 结尾时不重复）。
- 认证头：`openai-completions` 用 `Authorization: Bearer <key>`；`anthropic-messages` 用 `x-api-key: <key>` + `anthropic-version: 2023-06-01`。
- 只接受 2xx JSON；模型列表取 `data[]` 或 `models[]`；`id` 必填，`name` / `display_name` 可选。
- 出站策略：默认只允许公网 HTTPS；`MODEL_ALLOW_PRIVATE_ENDPOINTS=true` 时允许私网与 HTTP；link-local 与云元数据地址永远拒绝；请求前解析 DNS 并校验全部解析结果；不跟随重定向。
- 策略拒绝返回 `400 INVALID_REQUEST`；供应商错误使用第 7 节的 `PROVIDER_*` 错误码。
- 请求限制：`baseUrl` ≤ 1000 字符、`apiKey` ≤ 4096 字符；URL 内嵌凭证拒绝；单次探测超时 10 秒。

## 10. Fixtures

目录：

```
contract/v1/fixtures/
├── gateway/     pi 事件序列 → 期望领域事件序列
└── consumer/    领域事件序列（可截断）→ 消费者期望行为
```

每个文件是一个 JSON 对象：

```json
{
  "contractVersion": "1",
  "kind": "gateway",
  "name": "text-thinking",
  "description": "...",
  "input": {
    "run": { "runId": "run-1", "turnId": "turn-1", "sessionId": "session-1" },
    "events": [ ... ]
  },
  "expected": {
    "frames": [
      { "seq": 1, "event": "run_started", "data": { ... } }
    ],
    "invariants": {
      "firstEvent": "run_started",
      "lastEvent": "run_settled",
      "terminalCount": 1,
      "seqStartsAt": 1,
      "seqMonotonic": true,
      "contractVersion": "1"
    }
  }
}
```

- `kind: "gateway"` 由 gateway 的 golden test 消费，`input.events` 是简化后的 pi 事件记录。
- `kind: "consumer"` 由 Java/web 契约测试消费，`input.frames` 是领域事件帧；`expected.syntheticTerminal`、`expected.assistant`、`expected.userDeliveryStatus` 描述断流兜底行为。
- 测试按文件名排序，遍历对应 `kind` 的全部 fixture。
- `expected.frames[].seq` 为 1 基序号；SSE `id` 由 `run_started.data.runId` 与 `seq` 派生为 `<runId>:<seq>`。
- fixture 是纯数据，禁止注释、时间戳、随机值；相同输入必须得到相同输出。

## 11. 一致性检查清单

gateway：

- [ ] 线上 SSE 不出现 pi 事件名。
- [ ] 第一帧 `run_started`、最后一帧 `run_settled`，终态唯一。
- [ ] `seq` 连续、单调、从 1 开始；心跳不占 `seq`。
- [ ] 断连后不再写帧，并 abort 当前 Run。
- [ ] 工具回调带齐身份 header；身份不来自 body。
- [ ] `tool_progress` 有界、非阻塞、不进入模型上下文。

Java：

- [ ] 启动校验 `contractVersion` 主版本。
- [ ] 按 `message_end` / `tool_result` 落库，不逐 delta 落库。
- [ ] `turnId` 幂等；同一轮重试不产生重复行。
- [ ] 未收到 `run_settled` 时合成 `STREAM_INTERRUPTED` 并落 `partial`。
- [ ] 工具回调按 header 做用户隔离与审计，不信任 body 身份。
