# 用量配额验收记录

日期：2026-11-04（实现与验收完成日）
对应 spec：`docs/specs/2026-10-30-usage-quota.md`
关联：ADR-0032、ADR-0023
工单：`docs/tickets/usage-quota/01–04`

## 自动化

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `QuotaServiceTest` | 未配置不查库、额度内放行、恰好等于额度即拒绝、`Retry-After` 按重置时间、+08:00 月初边界、未配置仍报已用、缓存命中 | 6 例通过 |
| `UsageQuotaIntegrationTest` | 第一轮用超小额度后第二轮 429 `QUOTA_EXCEEDED`；`/api/usage/me` 的 `quota` 字段 | 2 例通过 |
| 全量 server 套件 | 回归 | 见本次提交的 CI |

## 端到端（一次性栈 + 本地 OpenAI 兼容桩）

`QUOTA_MONTHLY_TOKENS=100`，桩每次回复上报 2900 tokens：

```
turn1 → 200，事件流完整（run_started … run_settled）
turn2 → 429 {"error":{"code":"QUOTA_EXCEEDED",
             "message":"monthly token quota exhausted; resumes at 2026-11-30T16:00:00Z",
             "retryable":true}}
usage → {"enforced":true,"limitTokens":100,"usedTokens":2900,
         "remainingTokens":0,"resetsAt":"2026-11-30T16:00:00Z"}
```

`2026-11-30T16:00:00Z` = 2026-12-01 00:00（+08:00），月初边界与 `app.usage.utc-offset` 一致。

语义说明：配额检查发生在**轮次开始**，因此第一轮可以越过额度（2900 > 100），第二轮才被拦下——这是「不打断在飞轮次」的刻意取舍。

## 前端

- 设置页「用量」顶部显示「本月额度：已用 X / Y（Z%）· M 月 D 日重置」；未配置时显示「未设置配额 · 本月已用 N tokens」。
- 聊天页 `QUOTA_EXCEEDED`：先用通用文案，随后拉一次用量把「将于 M 月 D 日恢复」补上。
- 会话打开时拉一次用量，≥90% 显示非阻塞提示（每会话一次）。
- `typecheck`、`build` 通过。

## 残余风险

- **purge 会让本月已用量回落**：`runs` 行随会话删除而消失，实时聚合无法保留历史；若需要强一致，需要月度账本表（ADR-0032 已列为推迟项）。
- 并发轮次可同时通过检查（小幅超限），与「不打断在飞轮次」同性质。
- 判定路径不吃缓存（每次一轮一次 SUM），用量页展示才走 30s 缓存。
- 配额值目前只有部署级默认；per-user 覆盖等 admin 写操作切片。
