# Compaction 全局策略按模型窗口换算，上下文占比走新事件

Date: 2026-10-25

## Status

Accepted

## Context

pi 内置 compaction 的旋钮是 `enabled`、`reserveTokens`、`keepRecentTokens`，默认值分别为 `true`、16384、20000。`reserveTokens` 是「为下一次请求预留的余量」，触发条件是 `contextTokens > contextWindow - reserveTokens`；它不随模型窗口变化。我们在 M1 需要「策略可配」，同时用户看不到当前上下文占比，不知道何时会压缩。

可选项包括：固定绝对 reserve、按窗口比例换算、per-user/per-session 覆盖、按轮保留、REST 查询占比、把 compaction summary usage 计入 TPM。每条都牵涉不同范围。

## Decision

1. **M1 只做 gateway 全局配置**：`COMPACTION_ENABLED`、`COMPACTION_RESERVE_RATIO`（默认 0.2，夹紧 0.05–0.5）、`COMPACTION_KEEP_RECENT_TOKENS`（默认 20000）。不做 per-user/per-session 覆盖。
2. **按会话模型窗口换算**：`reserveTokens = max(1024, floor(contextWindow * ratio))`，`keepRecentTokens = min(configured, max(1, floor(contextWindow / 2)))`；窗口缺失回退 128k。
3. **每会话独立 SettingsManager**：保留 `~/.pi/agent` 原有设置，模型确定后 `applyOverrides({compaction})`；不写回磁盘；模型切换重建时重新计算。
4. **保留量按 token 近似轮数**：pi 没有按轮保留 API，不修改 cut point。
5. **上下文占比用新契约事件**：`context_usage { tokens, contextWindow, percent, compactionThresholdPercent, compactionEnabled }`；在 run 开始、每条 assistant `message_end`、`compaction_end` 后发送；`tokens/percent` 允许 `null`。
6. **占比只做实时展示**：不落 MySQL、不提供刷新后的 REST 查询；下一次 run 补齐。摘要正文查看保持 P1。
7. **compaction summary usage 不计入 FR-6 TPM**：TPM 只统计 assistant 对话 usage；精确计费留到后续。

## Considered Options(被否的备选)

- **M1 直接做 per-user/per-session 覆盖** — 需要用户设置表、Java↔gateway 字段、UI 与迁移；需求只要求策略可配，收益不足 → 推迟到 M2。
- **固定绝对 reserveTokens** — 不随 8k/128k/200k 模型变化，大窗口压缩过早或小窗口溢出 → 否决。
- **按轮保留最近 N 轮** — pi 只有 `keepRecentTokens`，实现轮级 cut point 要改 pi 内部 → 否决。
- **新增 `/context` REST 接口查询占比** — 需要轮询/额外鉴权与 Java 代理；事件推送已足够 → 否决。
- **把占比持久化，刷新后恢复** — 压缩后数值会过期，容易误导；实时事件更诚实 → 否决。
- **把 compaction summary usage 计入 TPM** — 契约无 usage、需新增字段与投影记账，且容易与下一轮 usage 重复 → 否决。

## Consequences

- 所有用户/会话共享一套比例策略；不同模型仍按各自窗口自动换算。M2 若做 per-user，需要新增用户设置与契约字段。
- 压缩后 `tokens/percent` 为 `null`，UI 显示「上下文统计中」直到下一条 assistant 回复；这是 pi 的正常状态。
- 小窗口模型下 1024 的最低 reserve 可能让触发点低于 80%；这是保底余量的取舍。
- 占比不持久化，刷新页面后在下一次 run 前不显示。
- 关闭 compaction 时只停止自动压缩，不删除已有原文；摘要正文查看仍不可用（P1）。
