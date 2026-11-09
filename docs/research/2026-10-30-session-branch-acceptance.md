# 会话分支 UI 验收记录

日期：2026-11-05（实现与验收完成日）
对应 spec：`docs/specs/2026-10-30-session-branch.md`
关联：ADR-0035、ADR-0006
工单：`docs/tickets/session-branch/01–04`

## 自动化

| 套件 | 覆盖 | 结果 |
|---|---|---|
| gateway `app.test.ts` | 分支机构：成功返回 `editorText` 且调用 `navigateTree`；活动 Run → 409 `SESSION_BUSY`；他人会话 → 403 | 2 例通过（gateway 141 tests 全绿） |
| `SessionBranchIntegrationTest` | 两轮对话后从第一条用户消息分支：历史只剩分叉点之前；库内被隐藏行 `active_branch=FALSE` 仍在；审计 `session.branch` 一条；他人消息 404；assistant 消息 400 | 2 例通过 |
| 契约 | `run_settled` 增加可选 `userEntryId`，README 已更新 | `validate.mjs` 通过 |
| Web | `npm run typecheck`、`npm run build` | 通过 |

## 关键实现

- gateway 在 prompt 结束后取 `getUserMessagesForForking().at(-1).entryId`，随 `run_settled.userEntryId` 下发；
- Java 在 `settleRun` 把该 id 写到本轮 user 行（`messages.pi_entry_id`）；
- 分支：`POST /api/chat/sessions/{id}/branch {messageId}` → 路由到持有者 → `navigateTree(entryId, {summarize:false})` → `UPDATE messages SET active_branch=FALSE WHERE id > 分叉点` → 清 Redis 最近消息缓存 → 审计；
- 历史分页、`existsBefore`、`maxIdBySession` 全部加 `active_branch = TRUE`；
- 前端只在 user 消息且 `piEntryId` 非空时显示「从这里重新开始」，确认后刷新历史并把原文回填输入框。

## 未覆盖

- 完整树视图 / 多分支切换（明确 out of scope）；
- 没有做浏览器级手工验收（前端只做 typecheck/build）；
- 分叉摘要（`summarize` 固定 false）。

## 残余风险

- `pi_entry_id` 只对新轮次生效；老会话看不到分支入口（设计如此）；
- 分支后旧消息仍计入历史用量与 token 统计（它们确实发生过）；
- 被隐藏的行只在库里可查，UI 没有恢复入口（留待树视图）。
