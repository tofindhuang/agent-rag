# FR-1 P2：会话分支 UI（从这里重新开始）

日期：2026-10-30
状态：已实现并验收（见 `docs/research/2026-10-30-session-branch-acceptance.md`）
关联 ADR：[0035](../adrs/0035-session-branch-ui.md)、[0006](../adrs/0006-persistence-ownership.md)、[0008](../adrs/0008-gateway-cold-start-local-session-files.md)
关联需求：FR-1（会话管理，P2 分支 UI）
涉及模块：gateway、server、web、契约

## Problem Statement

pi 的 session 是树，但 UI 只能线性往后走：用户想「改一下上一个问题重问」时只能新开会话，丢掉上下文。`navigateTree` 与 `getUserMessagesForForking` 已经具备，缺的是把它安全地接到业务层：entry id 对齐、旧分支隐藏、审计与并发保护。

## Solution

`run_settled` 携带本轮用户消息的 `userEntryId` → Java 存到 `messages.pi_entry_id`；用户在消息上点「从这里重新开始」→ Java 路由到持有会话的 gateway → `navigateTree(entryId, {summarize:false})` → Java 把之后的消息置为 `active_branch=false` → 返回原文回填输入框。

## Implementation Decisions

### 1. 契约（纯增量）

`run_settled.data` 增加可选 `userEntryId`：gateway 在 prompt 结束后从 `getUserMessagesForForking()` 取最后一条用户条目的 id。

### 2. Schema

`messages` 增加：

```sql
pi_entry_id VARCHAR(64) NULL,
active_branch BOOLEAN NOT NULL DEFAULT TRUE,
KEY idx_messages_session_active (session_id, active_branch, id)
```

- 投影在 `settleRun` 时按 `(session_id, turn_id)` 更新 user 行的 `pi_entry_id`。
- 历史分页、`existsBefore`、`maxIdBySession` 一律加 `active_branch = 1`。

### 3. gateway

- `POST /sessions/:id/branch { entryId }`：有活动 Run → 409 `SESSION_BUSY`；否则 `navigateTree(entryId, {summarize:false})`，返回 `{ editorText, cancelled }`。
- 未持有该会话 → 404（由 Java 路由保证打到持有者）。

### 4. server

- `POST /api/chat/sessions/{id}/branch { messageId }`：
  - 会话必须 active 且归属本人；消息必须属于该会话且 `pi_entry_id` 非空；
  - 路由到 gateway 调分支；成功后 `UPDATE messages SET active_branch=0 WHERE session_id=? AND id>?`，清 Redis 最近消息缓存；
  - 审计 `session.branch`（分叉点 + 隐藏条数）；返回 `{ editorText, hiddenCount }`。
- 进行中的轮次 → 409 `RUN_IN_PROGRESS`；无 entry id → 400 `INVALID_REQUEST`。

### 5. web

- 用户消息上的「从这里重新开始」动作（仅当该消息有 `piEntryId`）。
- 确认对话框说明「之前的内容会隐藏但保留」；成功后刷新历史并把原文回填输入框。

## Out of Scope

- 完整树视图与多分支切换；
- 分叉时生成摘要；
- 删除/恢复被放弃的分支；
- 分支的跨设备同步（依赖 pi session 文件，随归档恢复）。

## Acceptance

1. 完成一轮对话后，user 行有 `pi_entry_id`；没有该字段的老会话不显示入口。
2. 从第 N 条用户消息分支：之后的消息不再出现在历史接口里，但库里仍存在且 `active_branch=0`。
3. 分支成功后输入框回填该消息原文。
4. 有活动轮次时分支返回 409；无 entry id 返回 400；他人会话 404。
5. 审计出现 `session.branch`，detail 含隐藏条数。
6. gateway/server/web 测试与构建全绿。
