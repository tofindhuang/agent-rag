# 可观测与运营：admin 只读看板、用量与 trace

日期：2026-10-25
状态：待实现
关联需求：FR-7（P1）
关联 ADR：[0023](../adrs/0023-observability-mysql-read-only-admin.md)
上游记录：[M1 closeout](../tickets/m1-closeout/01-closeout-record.md)

## Problem Statement

FR-7 要求三件事：用量统计（token、执行次数、按用户/会话聚合、成本估算）、全链路 `request_id` trace、运营指标看板（活跃会话、compaction 频次、检索命中率、沙箱排队时长）。

现状：

- `runs` / `messages` 已持久化 `tokens_in/out`，`exec_records` 有 `request_id`、耗时与资源，但**没有任何聚合接口**。
- `request_id` 已贯通 gateway、工具回调与执行审计，但 `runs/messages` 没存，无法按它查询时间线。
- compaction 事件只在前端展示，不落库；沙箱排队时间未记录；`messages.details` 里的 `kb_search` 结果没有用于统计。
- 没有 admin 角色，只有每用户自助；Prometheus 仅暴露限流指标。

本 spec 交付**只读**的 admin 运营看板 + 用户自助用量页 + 基于 `request_id` 的对话轮次 trace。

## 范围

**做**：

- `role=admin` 的引导（环境变量）与服务端强制校验
- 用户自助用量页（今天 + 7/30 天 + Top 会话）
- admin 看板：总览、用户列表/详情、trace 查询（单路由三 Tab）
- 最小数据补齐：`runs.request_id/model_id`、`exec_records.queue_ms`、`compaction_events`
- 部署级模型价格表与服务端成本估算（CNY 默认）
- Recharts 图表（精确锁定 `3.10.1`）

**不做（本 spec 明确排除）**：

- 管理后台的**写操作**（用户/模型/KB/配额/密钥）——线 B，另立 spec
- 读操作审计表（只记 INFO 日志）
- 抗会话删除的用量账本（彻底删除会话会移除其历史用量，文档明示）
- embedding / rerank 的调用成本（目前没有按次计量）
- OpenTelemetry / Jaeger 等 tracing 后端
- 导出、实时推送、按用户时区、饼图/仪表盘等复杂图表
- 「无答案」查询与相关性指标（相关性由检索质量评测回答）

## Decisions

1. **消费形态**：产品内 admin 只读看板 + 用户自助用量；线 B（管理后台写操作）推迟到 M2 后段或更晚。
2. **权限边界**：admin 只读；不看会话标题、消息正文、代码、stdout/stderr、API key、KB 内容与 pi session 文件。
3. **角色模型**：`users.role ENUM('user','admin')`；`ADMIN_EMAILS` 环境变量在启动时提升已存在用户、注册命中时直接建 admin；不自动降级。
4. **角色校验**：角色不进 JWT；每个 `/api/admin/**` 请求按 `userId` 查库判断，撤权即时生效；`MeResponse` 增加 `role`。
5. **数据事实源**：聚合现有表（`runs` / `messages` / `exec_records`）+ 最少补齐；不建双写账本、不引入时序库。
6. **成本**：部署级价格表（按 `model_id`，每百万 token，含 input/output/cacheRead/cacheWrite）+ 服务端按 run 的四类 token 计算；未知模型价格显示「—」；默认币种 CNY，单部署单币种。
7. **成本范围**：仅聊天模型 token；缓存读/写 token 单独持久化并计价（`usage.input` 不含缓存，已核对 pi 语义）；未配置缓存单价时回落 input 单价。
8. **指标口径**：活跃 run 数 + 15 分钟活跃会话；compaction 次数来自事件表；检索命中率 = 有返回片段的成功 `kb_search` 占比；沙箱排队时长取 admission 等待。
9. **trace 深度**：DB 关联 + 产品内只读时间线；按 `request_id`（主）/`turn_id`/`run_id` 查询；只覆盖对话轮次。
10. **隐私**：admin 可见 email/userId/sessionId/model/token/成本/工具名/状态/耗时/时间戳；不可见标题、正文、代码、stdout、密钥、KB 内容。
11. **用户页**：今天卡 + 7/30 天趋势 + 按天表 + Top 会话（自己的标题可显示）；入口在设置页。
12. **admin 页**：单路由 `/admin` + 总览/用户/trace 三 Tab；入口仅在 `role=admin` 时出现在设置页。
13. **图表**：Recharts `3.10.1` 精确锁定；只做折线/柱状；颜色用现有 CSS token。
14. **用量保留**：不抗会话彻底删除；删除后历史用量与执行次数一并消失，文档明示。
15. **时区与窗口**：部署级 UTC 偏移（默认 `+08:00`）分桶；7/30 天窗口；用户页默认 30 天，admin 总览默认 7 天。
16. **刷新**：admin 总览每 60 秒自动刷新（页面隐藏时暂停）；其余页面加载时刷新 + 手动按钮。
17. **API 命名空间**：admin 走 `/api/admin/**`，非 admin 返回 403 `FORBIDDEN`；用户自助走 `/api/usage/me`；不伪装 404。
18. **分页**：用户列表与会话列表默认 20、上限 100；用户列表默认按最后活跃倒序，可切成本/token 排序。
19. **admin 读审计**：仅结构化 INFO 日志（adminUserId、目标类型与 ID、endpoint）；不建读审计表；沿用普通 per-user 限流。

## 角色与鉴权

- `ADMIN_EMAILS`：逗号分隔，按注册时的规范化 email（小写）匹配。
- 启动时执行一次 `UPDATE users SET role='admin' WHERE email IN (...)`；新注册命中则直接写入 `role='admin'`。
- 不自动降级；需要降级时由线 B 或人工处理（M2 不提供 UI）。
- `MeResponse` 增加 `role`；web 仅用它控制入口显示，服务端仍强制校验。
- admin 读请求记录 INFO：`admin=<userId> target=<type>:<id> endpoint=<path>`；不记录 email 与内容。

## 数据模型（MySQL，Flyway up-only）

新迁移 `V20261025150000__observability.up.sql`，幂等（`information_schema` 守卫 + `IF NOT EXISTS`），无 down 文件。

### 角色

```sql
SET @role_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'role'
);
SET @role_ddl := IF(@role_exists = 0,
    "ALTER TABLE users ADD COLUMN role ENUM('user','admin') NOT NULL DEFAULT 'user' AFTER password_hash",
    'SELECT 1');
PREPARE role_stmt FROM @role_ddl; EXECUTE role_stmt; DEALLOCATE PREPARE role_stmt;
```

### run 级关联与模型快照

```sql
ALTER TABLE runs
    ADD COLUMN request_id VARCHAR(128) NULL AFTER gateway_run_id,
    ADD COLUMN model_id VARCHAR(200) NULL AFTER request_id,
    ADD COLUMN cache_read_tokens BIGINT NULL AFTER tokens_out,
    ADD COLUMN cache_write_tokens BIGINT NULL AFTER cache_read_tokens;

ALTER TABLE runs
    ADD KEY idx_runs_request (request_id),
    ADD KEY idx_runs_turn (turn_id),
    ADD KEY idx_runs_started (started_at),
    ADD KEY idx_runs_status_started (status, started_at),
    ADD KEY idx_runs_model_started (model_id, started_at);
```

- `request_id`：发起 prompt 时生成，和发给 gateway 的 header 一致。
- `model_id`：prompt 开始时快照当前会话的模型标识（用户模型取 `provider_models.model_id`；部署系统默认记为固定哨兵 `system`，价格表可用该键）；用于价格表匹配。
- 旧行两列为 NULL：trace 只覆盖新 run，成本对旧 run 显示「—」。

### 执行排队

```sql
ALTER TABLE exec_records ADD COLUMN queue_ms INT NULL AFTER duration_ms;
```

- `queue_ms` = `admission.acquire` 前后耗时；旧行为 NULL。

### compaction 事件

```sql
CREATE TABLE IF NOT EXISTS compaction_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id CHAR(36) NOT NULL,
    run_id CHAR(36) NULL,
    turn_id CHAR(36) NULL,
    request_id VARCHAR(128) NULL,
    reason VARCHAR(64) NULL,
    tokens_before BIGINT NULL,
    tokens_after BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_compaction_user_created (user_id, created_at),
    KEY idx_compaction_session_created (session_id, created_at),
    CONSTRAINT fk_compaction_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_compaction_session FOREIGN KEY (session_id)
        REFERENCES sessions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- 由 `compaction` SSE 事件写入；`reason`、`tokensBefore`、`tokensAfter` 原样保存。
- 随会话彻底删除而删除（与「不抗删除」的用量口径一致）。

### 检索统计

不新增表：`messages.details` 已有 `toolName` 与 `passages`。`messages` 增加缓存 token 列与时间索引：

```sql
ALTER TABLE messages
    ADD COLUMN cache_read_tokens BIGINT NULL AFTER tokens_out,
    ADD COLUMN cache_write_tokens BIGINT NULL AFTER cache_read_tokens,
    ADD KEY idx_messages_created (created_at);
```

## 配置

```yaml
app:
  usage:
    currency: CNY                 # 单部署单币种；可改为 USD
    utc-offset: "+08:00"          # 按天分桶的部署时区偏移
    active-session-window: 15m    # 活跃会话窗口
    model-prices:                 # 每百万 token；未配置的模型成本为不可用
      deepseek-chat:
        input: 2.0
        output: 8.0
        cache-read: 0.5
        cache-write: 2.0
```

- 环境变量：`ADMIN_EMAILS`（逗号分隔）。
- 价格表为示例，实际值由部署者维护；线 B 再做在线管理。
- 成本 = Σ( run.tokens_in × input + run.tokens_out × output
  + run.cache_read_tokens × cacheRead + run.cache_write_tokens × cacheWrite ) / 1_000_000。
- `cache-read` / `cache-write` 可省略，省略时回落到 `input` 单价；`model_id` 不在表中 → 计入 `unpricedRuns`，不计成本。

## 指标口径

| 指标 | 定义 | 来源 |
|---|---|---|
| `runningRuns` | `status='running'` 的 run 数 | `runs` |
| `activeSessions15m` | 窗口内（默认 15 分钟）有 run 活动的去重会话数（`started_at` 或 `settled_at` 落在窗口） | `runs` + `sessions.deleted_at IS NULL` |
| `tokensIn/Out` | 窗口内 run 的**非缓存**输入/输出 token 求和 | `runs` |
| `cacheRead/WriteTokens` | 窗口内缓存读/写 token 求和 | `runs` |
| `cost` | 四类 token 按价格表求和；`unpricedRuns` 只统计有 token 但无价格的 run | `runs` + 价格表 |
| `executions` | 窗口内执行次数；成功/失败/超时分别计数 | `exec_records.status` |
| `compactions` | 事件次数与 tokensBefore/After 求和 | `compaction_events` |
| `retrieval.calls` | `details.toolName='kb_search'` 的工具消息数 | `messages` |
| `retrieval.hitRate` | `passages` 非空的调用 / 可解析 details 的调用 | `messages.details` |
| `retrieval.avgPassages` | 有命中调用的平均片段数 | 同上 |
| `retrieval.failedCalls` | `kb_search` 但 details 缺失/不可解析的调用（旧版本无 details 的行也会计入） | 同上 |
| `sandbox.queueP50/P95` | 窗口内 `queue_ms` 的最近秩百分位 | `exec_records` |
| `sandbox.durationP50/P95` | 窗口内 `duration_ms` 的最近秩百分位 | `exec_records` |

分桶规则：所有「按天」数据用 `DATE(CONVERT_TZ(ts, '+00:00', :utcOffset))`；「今天」是部署时区的当天 00:00 至当前。百分位在 Java 侧对窗口内样本排序计算，样本为空返回 null。

## API

所有接口需登录；`/api/admin/**` 额外要求 `role=admin`，否则 403 `FORBIDDEN`。窗口参数 `window=7d|30d`（默认按页面），非法值 400 `INVALID_REQUEST`。

`GET /api/admin/ping` 为访问冒烟接口，返回 `{"role":"admin"}`；真实看板接口见下。

### 用户自助

`GET /api/usage/me?window=30d`

```json
{
  "currency": "CNY",
  "today": { "tokensIn": 1200, "tokensOut": 340, "cacheReadTokens": 800,
             "cacheWriteTokens": 120, "cost": 0.05, "unpricedRuns": 0,
             "executions": 2, "searches": 3 },
  "window": { "days": 30, "tokensIn": 52000, "tokensOut": 14000,
              "cacheReadTokens": 30000, "cacheWriteTokens": 4000,
              "cost": 1.23, "unpricedRuns": 4, "executions": 18, "searches": 40 },
  "daily": [ { "date": "2026-10-01", "tokensIn": 100, "tokensOut": 20,
               "cacheReadTokens": 40, "cacheWriteTokens": 5,
               "cost": 0.01, "unpricedRuns": 0, "executions": 1, "searches": 2 } ],
  "topSessions": [ { "sessionId": "uuid", "title": "会话标题", "lastActiveAt": "2026-10-02T...",
                     "tokensIn": 4000, "tokensOut": 900, "cost": 0.12, "executions": 2 } ]
}
```

- `topSessions` 只返回当前用户**所选窗口内** token 最高的 10 个；标题属于用户自己的数据。
- `cost` 为已定价部分；`unpricedRuns > 0` 时前端标注「部分模型未配置价格」。

### admin 总览

`GET /api/admin/overview?window=7d`

```json
{
  "window": { "days": 7 },
  "currency": "CNY",
  "active": { "runningRuns": 1, "activeSessions15m": 4 },
  "usage": { "tokensIn": 100000, "tokensOut": 25000, "cacheReadTokens": 60000,
             "cacheWriteTokens": 8000, "cost": 2.4, "unpricedRuns": 3 },
  "executions": { "total": 40, "succeeded": 38, "failed": 1, "timedOut": 1 },
  "retrieval": { "calls": 120, "hitCalls": 108, "hitRate": 0.9,
                 "avgPassages": 3.4, "failedCalls": 2 },
  "compactions": { "count": 6, "tokensBefore": 900000, "tokensAfter": 210000 },
  "sandbox": { "executions": 40, "queueP50Ms": 12, "queueP95Ms": 43,
               "durationP50Ms": 640, "durationP95Ms": 2100 },
  "daily": [ { "date": "2026-10-20", "tokensIn": 12000, "tokensOut": 3000,
               "cacheReadTokens": 7000, "cacheWriteTokens": 900,
               "cost": 0.3, "executions": 5, "compactions": 1, "hitRate": 0.92 } ]
}
```

### admin 用户

`GET /api/admin/users?query=&page=1&size=20&window=7d&sort=lastActive|cost|tokens`

```json
{
  "items": [ { "userId": 2, "email": "a@example.com", "createdAt": "...",
               "lastActiveAt": "...", "sessions": 3, "tokensIn": 1000, "tokensOut": 200,
               "cacheReadTokens": 600, "cacheWriteTokens": 80,
               "cost": 0.02, "unpricedRuns": 0, "executions": 4, "searches": 9 } ],
  "total": 42, "page": 1, "size": 20
}
```

`GET /api/admin/users/{userId}?window=7d&page=1&size=20`

```json
{
  "user": { "userId": 2, "email": "a@example.com", "createdAt": "...", "lastActiveAt": "..." },
  "today": { "tokensIn": 0, "tokensOut": 0, "cacheReadTokens": 0,
             "cacheWriteTokens": 0, "cost": 0, "unpricedRuns": 0,
             "executions": 0, "searches": 0 },
  "window": { "days": 7, "tokensIn": 1000, "tokensOut": 200,
              "cacheReadTokens": 600, "cacheWriteTokens": 80,
              "cost": 0.02, "unpricedRuns": 0, "executions": 4, "searches": 9 },
  "daily": [ { "date": "2026-10-20", "tokensIn": 1000, "tokensOut": 200,
               "cacheReadTokens": 600, "cacheWriteTokens": 80,
               "cost": 0.02, "executions": 4, "searches": 9 } ],
  "models": [ { "modelId": "deepseek-chat", "tokensIn": 900, "tokensOut": 180,
                "cacheReadTokens": 600, "cacheWriteTokens": 80, "cost": 0.02 } ],
  "sessions": { "items": [ { "sessionId": "uuid", "createdAt": "...", "lastActiveAt": "...",
                             "modelId": "deepseek-chat", "tokensIn": 1000, "tokensOut": 200,
                             "cacheReadTokens": 600, "cacheWriteTokens": 80,
                             "cost": 0.02, "executions": 4 } ],
                "total": 1, "page": 1, "size": 20 }
}
```

- 不含会话标题、消息正文、代码、stdout/stderr。

### admin trace

`GET /api/admin/trace?requestId=...`（或 `turnId`/`runId`，三者只能给一个）

```json
{
  "summary": { "requestId": "...", "userId": 2, "email": "a@example.com",
               "sessionId": "...", "turnId": "...", "runId": "...",
               "modelId": "deepseek-chat", "status": "completed",
               "tokensIn": 1200, "tokensOut": 340, "cacheReadTokens": 800,
               "cacheWriteTokens": 120, "cost": 0.05,
               "startedAt": "...", "settledAt": "..." },
  "timeline": [
    { "type": "run_started", "at": "...", "modelId": "deepseek-chat" },
    { "type": "assistant", "at": "...", "seq": 1, "tokensIn": 900, "tokensOut": 120,
      "cacheReadTokens": 600, "cacheWriteTokens": 80,
      "toolCalls": [ { "name": "kb_search", "toolCallId": "..." } ] },
    { "type": "tool", "at": "...", "name": "kb_search", "passages": 3, "topScore": 0.82 },
    { "type": "tool", "at": "...", "name": "run_code", "execId": "...", "status": "succeeded",
      "durationMs": 640, "queueMs": 12, "exitCode": 0 },
    { "type": "run_settled", "at": "...", "status": "completed",
      "errorCode": null, "errorMessage": null }
  ]
}
```

- 节点缺失时写 `"missing": true`，不臆造。
- `errorMessage` 截断 200 字符；不返回消息正文、代码、stdout/stderr。
- 查询键不存在 → 404 `NOT_FOUND`；多个键同时给 → 400。

## 前端

### 用户用量（设置页）

- 设置页新增「用量」区块（与模型、记忆、账户并列），所有用户可见。
- 今天卡：输入/输出/缓存读/缓存写 token、成本、执行次数、检索次数；`unpricedRuns > 0` 显示「部分模型未配置价格」。
- 7/30 天切换；折线图（总 token 趋势）、柱状图（成本/执行）；按天表（输入/输出/缓存读/缓存写/成本/执行/检索）；Top 会话列表（自己的标题）。
- 加载态、错误态、空态；成本显示「—」而非 0。

### admin `/admin`

- 单路由、三 Tab：总览 / 用户 / trace；进入时校验 `role`，非 admin 显示「没有权限」。
- 总览：指标卡、折线/柱状图、窗口切换、60 秒自动刷新（`visibilitychange` 暂停）、手动刷新。
- 用户：搜索（email/userId）、排序、分页；详情页展示按天图、模型分布、会话 ID 列表。
- trace：按 ID 查询，summary + 时间线；错误提示清晰。
- 图表统一 Recharts `3.10.1`；仅客户端组件；颜色用 CSS 变量，跟随明暗主题。
- 设置页仅在 `role=admin` 时显示「运营看板」链接。

## 埋点与投影

- prompt 开始时生成/沿用 `request_id`，写入 `runs.request_id`；同时把会话模型标识写入 `runs.model_id`。
- `run_settled` 已有 tokens 汇总；无需新增 token 字段。
- `message_end.usage` 的 `input/output/cacheRead/cacheWrite` 投影到 `messages` 与 `runs` 对应列（`tokens_in/out` 保持「非缓存」语义）。
- `compaction` SSE 事件到达时插入 `compaction_events`；不改变事件本身。
- `admission.acquire` 前后计时，写入 `exec_records.queue_ms`；失败路径不写。
- `kb_search` 的 details 已含 `toolName` 与 `passages`；不新增字段。

## 隐私与安全

- admin 可见字段严格按 Decisions 10；所有 admin API 响应不得包含 `content/thinking/code/stdout/stderr/apiKey/originalKey` 等字段。
- admin 读操作 INFO 日志；不建读审计表。
- admin API 沿用普通 per-user RPM/TPM 限流，不豁免。
- 前端隐藏入口只是体验；服务端 403 是唯一安全边界。
- 彻底删除会话会删除其 runs/messages/exec_records/compaction_events，历史用量随之减少；文档与 UI 明示。

## 验收标准

1. `ADMIN_EMAILS`：启动提升存量用户、注册命中直接成为 admin；非 admin 访问 `/api/admin/**` 返回 403；DB 中改为 `user` 后下一次请求立即失效。
2. 用户页：今天/7d/30d 的输入/输出/缓存读/缓存写与成本与 fixture SQL 一致；未定价模型显示「—」并提示部分成本；Top 会话只含本人数据。
3. admin 总览：running/active、token/成本、执行成功失败、compaction、检索命中率、排队 p50/p95 与 fixture 一致；compaction SSE 能落事件；`queue_ms` 能落值。
4. admin 用户列表：email/userId 搜索、排序、分页正确；详情页不含标题/正文/代码；会话列表仅 ID 与元数据。
5. trace：`request_id`/`turn_id`/`run_id` 均可查询；时间线节点与顺序正确；缺失节点标注；不存在的 ID 404；多键 400。
6. 时区：跨午夜 fixture 在 `+08:00` 下分桶正确；改成 `+00:00` 后日期边界相应变化。
7. 自动刷新：admin 总览 60 秒刷新，页面隐藏时暂停；其他页面仅手动刷新。
8. 会话彻底删除后，用量/执行/compaction 计数相应减少（符合明示口径）。
9. `server` 全量测试、web `typecheck`/`build` 通过；Recharts 精确锁定 `3.10.1`；新增代码无 `any`。

## 测试计划（TDD 缝）

| 层 | 缝 | 用例 |
|---|---|---|
| 单元 | 成本计算 | 四类 token 分开计价、缓存价缺省回落 input、有价/无价/全无价、币种标签 |
| 单元 | 分桶与百分位 | 跨午夜、7/30 天、最近秩 p50/p95、空样本 |
| 单元 | 检索命中率 | passages 空/非空、details 缺失、失败调用 |
| 集成 | 角色守卫 | 启动提升、注册命中、403、撤权即时生效 |
| 集成 | 用户用量 | today/7d/30d、Top 会话、未定价计数、本人隔离 |
| 集成 | admin 总览/用户 | fixture 数据、搜索排序分页、字段白名单（无正文） |
| 集成 | trace | 三种键、时间线组装、缺失节点、404/400 |
| 集成 | 埋点 | compaction 事件落库、queue_ms 写入、request_id 落 run |
| 前端 | 构建与类型 | `npm run typecheck`、`npm run build`；图表空态/错误态人工验收 |

## 后续（线 B / 更晚）

- 管理后台写操作：用户、模型、KB、配额、密钥；写审计表与二次确认。
- 若需要账单或终身用量：新增 append-only 用量账本，看板 API 不变。
- OpenTelemetry / 分布式 tracing；embedding/rerank 成本；按用户时区；导出；实时推送。
- 无答案查询与相关性指标（依赖检索质量评测的阈值语义）。
