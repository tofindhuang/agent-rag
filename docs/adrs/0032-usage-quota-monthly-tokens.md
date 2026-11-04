# 用量配额：按月 token、部署级默认、实时聚合

Date: 2026-10-30

## Status

Accepted

## Context

FR-7 只做了「用量统计」：`runs` 的 token 按日/窗口聚合，乘部署级价格表得到成本估算。系统没有任何**上限**——一个用户（或一个失控的 agent 循环）可以在一个月内跑掉任意多的 token。多个既有 spec 把「计费/配额」列为 Out of Scope，M2 closeout 把 M3 主题定为「运营与商业化」，配额是第一块地基。

同时有两个约束：

- 价格表是部署级配置，**未配置价格的模型会被标成 unpriced**，所以按「钱」做配额会在覆盖不全时给出不可解释的结果；
- `runs` 表没有 `user_id`，用量一直是 `runs JOIN sessions` 按 `sessions.user_id` 过滤。

## Decision

1. **维度：月度 token，per user**。口径 = `tokens_in + tokens_out + cache_read_tokens + cache_write_tokens`，与 `/api/usage/me` 完全一致。不做成本配额、不做 run_code / kb_search 次数配额（后续可作为第二维度叠加）。
2. **粒度：自然月**，按 `app.usage.utc-offset`（默认 +08:00）切分；`resetsAt` 返回下月 1 日 00:00 的 ISO 时间。
3. **判定点：`openPrompt` 单点**，位于 TPM 检查之前。`kb_search` / `run_code` 都发生在轮次内，天然被覆盖。
4. **超额行为：硬限 429 + `QUOTA_EXCEEDED`**，`Retry-After` = 距重置的秒数；已在执行的轮次不打断（可能小幅超限）。
5. **计量：实时聚合 + 30 秒 Redis 缓存**。数据库是唯一真相；缓存只为挡住突发的重复检查。并发轮次可能同时通过检查（小幅超限，接受）。
6. **取值：部署级默认** `QUOTA_MONTHLY_TOKENS`（`0` = 不限制）。per-user 覆盖留到「admin 写操作」切片，届时只是多查一列。
7. **未配置时零开销**：不查库、不缓存、不改变响应形状（`quota.enforced=false`）。

## Considered Options（被否的备选）

- **按成本配额** — 价格表覆盖不全，unpriced 模型无法给出可解释的上限 → 否决。
- **按日配额** — 反馈快但「今天用完了等明天」对正常用户不友好，且商业口径通常是按月 → 否决。
- **软限（只告警）** — 等于没有上限 → 否决。
- **Redis 计数器 + 对账** — 判定 O(1)，但要处理重启丢数、双写漂移与对账任务；配额判断错了比慢一点严重 → 否决。
- **独立月度账本表** — 查询 O(1) 且无漂移，但要在 settle 事务里多写一张表并回填历史 → 推迟（真到瓶颈时可从 `runs` 回填）。
- **只在建会话时检查** — 用户可以复用同一会话，形同虚设 → 否决。
- **预留/回补（reservation）机制** — 需要预扣、结算、超时回滚三套状态，当前规模不值得 → 否决。

## Consequences

- 升级默认 `QUOTA_MONTHLY_TOKENS=0`，行为与今天完全一致；开启后超额用户会收到明确错误与重置时间。
- 月度聚合依赖 `runs(session_id, started_at)` 复合索引（新增迁移）。
- 30 秒缓存与并发检查意味着「最多多放行几个轮次」，这是刻意接受的近似，需写进验收记录。
- 配额是运营指标：超额会加 Prometheus 计数器与 warn 日志；admin 只读看板本轮不加配额面板（留给 admin 写操作切片统一做）。
- 未来加 per-user 覆盖时，判定逻辑不变，只需在取值处引入覆盖来源。
