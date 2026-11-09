# 会话分支 UI：从某条用户消息重新开始

Date: 2026-10-30

## Status

Accepted

## Context

pi 的 session 本身就是树：`SessionManager` 有 `getLeafId`/`getTree`，`AgentSession.navigateTree(entryId)` 能把 leaf 移回更早的条目，`getUserMessagesForForking()` 直接给出用户消息的 `entryId`。但 agent-core 一直没有暴露它：FR-1 把「会话分支 UI」标成 P2，理由是「pi 原生支持、本期只保留能力」。

现在要把它补上。约束有三条：

- Java 的消息投影（`messages` 表）是**线性**的，没有 pi entry id；
- 分支之后，被放弃的那段历史**不能删**（FR-4 的「不失忆」原则）；
- 分支是「改写会话状态」的操作，必须可审计、不能与运行中的轮次相撞。

## Decision

1. **只做「从这里重新开始」**（等价于 ChatGPT 的 edit / regenerate from here），不做完整树视图。完整树需要把 pi 的 entry 树整体投影到 Java 与树形 UI，量级是这一版的 3–4 倍。
2. **精确对齐靠契约增量**：`run_settled` 增加可选 `userEntryId`（该轮用户消息的 pi entry id），Java 存进 `messages.pi_entry_id`。老 gateway 不带该字段时该列为空，**分支入口隐藏**——不按文本/序号猜，避免分错支。
3. **分支不生成摘要**：`navigateTree(entryId, { summarize: false })`，不产生 LLM 调用、不计配额；旧分支原文留在 pi session 与归档里。
4. **隐藏而非删除**：`messages` 增加 `active_branch`（默认 true），分支时把该消息之后的行置为 false，历史接口只返回 true 的行。将来做树视图时这些行就是「另一条分支」。
5. **审计**：新增 `session.branch`，记录分叉点消息与隐藏条数。
6. **并发与路由**：Java 校验归属与会话空闲后，把请求路由到持有该会话的 gateway 实例（与 ensure/prompt 同一套路由），gateway 侧有活动 Run 时返回 409。

## Considered Options（被否的备选）

- **完整树视图 / 多分支切换** — 需要 entry 树投影、分支元数据与树形 UI，远超一版 → 推迟。
- **按「第 N 条用户消息」对齐 entryId** — 分叉过或删过消息就会错位，可能让用户丢掉一段对话 → 否决。
- **只在 gateway 侧做分支、Java 不管** — 刷新后 Java 仍返回旧分支，UI 与真实上下文不一致 → 否决。
- **分叉时生成摘要** — 引入额外 LLM 调用与计费，且当前没有展示摘要的位置 → 否决。
- **删除被放弃的消息** — 违反 FR-4 的「原文可回捞」→ 否决。

## Consequences

- 分支后历史变短、compaction 上下文也随之变短；旧分支只在 pi session 文件与归档里存在（`active_branch=0` 的行仍可查库）。
- 分支不消耗 token，但会让命中率/用量统计里的旧轮次仍计入历史用量（它们确实发生过了）。
- 老会话（无 `pi_entry_id`）看不到分支入口；这符合「不确定就不猜」。
- 将来做完整树视图时，`pi_entry_id` + `active_branch` 已经是必要基础。
