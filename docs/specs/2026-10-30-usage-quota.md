# M3 切片 1：用量配额

日期：2026-10-30
状态：已实现并验收（见 `docs/research/2026-10-30-usage-quota-acceptance.md`）
关联 ADR：[0032](../adrs/0032-usage-quota-monthly-tokens.md)、[0023](../adrs/0023-observability-mysql-read-only-admin.md)、[0030](../adrs/0030-token-bucket-rate-limit-and-trusted-proxies.md)
关联需求：FR-7（用量与运营）、§7 M3（运营与商业化）
涉及模块：server、web、docs

## Problem Statement

系统能统计用量（`runs` 的 token + 价格表 → 成本估算），但没有任何上限：单用户可以在一个月内消耗任意多 token。既有 spec 把「计费/配额」列为 Out of Scope，M3 主题是运营与商业化，配额是第一步。

## Solution

- 新增部署级配置 `QUOTA_MONTHLY_TOKENS`（默认 0 = 不限制）。
- 轮次开始（`openPrompt`）时实时聚合本月已用 token 并判定；超额返回 429 `QUOTA_EXCEEDED`（`Retry-After` = 距下月 1 日秒数）。
- `/api/usage/me` 返回 `quota` 对象；设置页显示本月额度使用率，聊天页在超额与 ≥90% 时给出提示。
- 未配置配额时零行为变化、零额外查询。

## Implementation Decisions

### 1. 配置与月度边界

- `app.quota.monthly-tokens`（`QUOTA_MONTHLY_TOKENS`），默认 `0`；`<= 0` 表示不限制。
- 月份边界用 `app.usage.utc-offset`（默认 +08:00）：`monthStart(now)`、`nextMonthStart(now)`。
- `resetsAt` = 下月 1 日 00:00 的 ISO 时间。

### 2. 计量

- SQL（与用量页同形状）：`SUM(tokens_in + tokens_out + cache_read_tokens + cache_write_tokens)`，`runs JOIN sessions`，条件 `sessions.user_id = ? AND runs.started_at >= monthStart`。
- 新增迁移索引 `runs(session_id, started_at)`（up-only 幂等）。
- 计入所有已有 token 记录的 run，**不区分状态**（aborted/failed 也可能消耗 token）。
- Redis 缓存：`quota:used:<userId>:<yyyy-MM>`，TTL 30 秒；**只用于用量页展示**。判定路径不吃缓存——否则缓存窗口内会成批放行。

### 3. 判定

- `QuotaService.status(userId)` → `{ enforced, limitTokens, usedTokens, remainingTokens, resetsAt }`。
- `requireWithinQuota(userId)`：未启用直接返回；否则 `used >= limit` 抛 `ApiException(QUOTA_EXCEEDED, retryAfter)`。
- 接入点：`ChatServiceImpl.openPrompt`，在 TPM 检查**之前**。
- 超额打 warn 日志 + `agentcore_quota_rejections_total` 计数器。
- 已在执行的轮次不打断（检查只在轮次开始）。

### 4. 接口

`GET /api/usage/me` 增加：

```json
"quota": {
  "enforced": true,
  "limitTokens": 2000000,
  "usedTokens": 1450000,
  "remainingTokens": 550000,
  "resetsAt": "2026-11-01T00:00:00Z"
}
```

未配置时 `enforced=false`、`limitTokens/remainingTokens` 为 null、`usedTokens` 仍给出（用户仍想知道用了多少）。

### 5. 前端

1. 设置页「用量」区顶部：`本月额度：已用 1,450,000 / 2,000,000（72.5%）· 11 月 1 日重置`；未配置显示「未设置配额」。
2. 聊天页超额：`QUOTA_EXCEEDED` 时显示「本月额度已用完，将于 11 月 1 日恢复」（不显示裸 429 文案）。
3. 聊天页 ≥90%：非阻塞提示「本月额度已用 92%，超额后将暂停对话」，每个会话只提示一次。

## Out of Scope

- per-user 配额覆盖与 admin 写操作（下一片）；
- 成本/次数维度配额；
- 预留-回补机制与精确并发控制；
- admin 看板的配额面板；
- 超额自动降级到更便宜模型。

## Acceptance

1. `QUOTA_MONTHLY_TOKENS=0` 时：不查库、不缓存、`quota.enforced=false`，既有测试全绿。
2. 开启后：已用 ≥ 额度 → `openPrompt` 返回 429 `QUOTA_EXCEEDED` + `Retry-After`（距下月 1 日）；`/api/usage/me` 的 `quota` 字段口径与判定一致。
3. 已用 < 额度：轮次正常，响应不变。
4. 月度边界：跨月后已用量归零（用固定时钟单测覆盖）。
5. 前端：用量页显示额度行；超额文案正确；≥90% 提示出现且每会话只出现一次；`typecheck`/`build` 通过。
6. 超额计数器出现在 Prometheus 输出。
