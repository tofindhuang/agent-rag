# 历史消息引用恢复（messages.details）

日期：2026-10-24
状态：已实现（ticket 09）
关联需求：FR-2（知识库 / RAG）
关联 ADR：[0015](../adrs/0015-persist-tool-result-details.md)
上游 spec：[2026-10-23-knowledge-base-rag.md](2026-10-23-knowledge-base-rag.md)

## Problem Statement

引用目前只在当轮 SSE 中存在：`tool_result.details` 到达 Java 后，`ChatServiceImpl` 只把 `content` 交给 `ChatProjectionService.appendTool`，`messages` 表没有存 details 的列，历史接口也不返回。刷新页面或翻历史后，助手回答下方的引用块消失。

用户明确要求历史可恢复。要求：实时与历史共用同一套解析/渲染；旧消息可降级；不重跑检索、不引入独立引用表。

## Decisions

1. **存储**：`messages.details JSON NULL`，只写 `role='tool'` 行；内容为 gateway 的 `tool_result.details` 原样 JSON。
2. **上限**：序列化后 ≤ 8 KiB 才写；超过则丢弃整个 details（存 NULL + WARN），不截断。
3. **历史接口**：`MessageSummary` 增加 `details`，返回解析后的 JSON 对象；旧行/非 tool 行为 `null`。
4. **不回填**：旧消息无法重建，老会话保持无引用；这是明确降级。
5. **生命周期**：details 跟随 message 行，会话永久删除时一起删；不新增清理任务、不做跨消息聚合。
6. **前端**：`types/gateway.ts` 的 `parseCitationPassages` 同时服务实时与历史；`toHistoryMessages` 把 details 解析后挂到 `ToolActivity.passages`，渲染与去重逻辑复用票 07。

## 数据模型（MySQL，Flyway up-only）

新增迁移 `V20261024190000__add_message_details.up.sql`：

```sql
-- 幂等：先查 information_schema，再执行 ALTER；重复重放只命中守卫。
SET @exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'messages'
      AND COLUMN_NAME = 'details'
);
SET @ddl := IF(@exists = 0,
    'ALTER TABLE messages ADD COLUMN details JSON NULL AFTER tool_calls',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

- `Message` 领域对象与 `MessageDao.xml` 的列清单、upsert 同步增加 `details`。
- `ChatProjectionService.appendTool` 签名增加 `String detailsJson`；`ChatProjectionServiceImpl` 用 8 KiB 上限判断后写入。
- `ChatServiceImpl` 取 `data.path("details")`：缺失/`null` → null；否则 `objectMapper.writeValueAsString` 后交给投影。

## API（Java ↔ web，加法）

`GET /api/chat/sessions/{id}/messages` 的 `items[]`：

```json
{
  "id": 42,
  "turnId": "...",
  "ordinal": 7,
  "role": "tool",
  "content": "[1] ...",
  "thinking": null,
  "status": null,
  "deliveryStatus": null,
  "createdAt": "...",
  "details": {
    "count": 2,
    "passages": [
      { "passageId": "...", "documentId": "...", "seq": 3, "page": 12,
        "title": "员工手册.pdf", "content": "...", "score": 0.93, "reranked": true }
    ]
  }
}
```

- `details` 为可选字段；非 tool 行、旧行、超限行均为 `null`。
- 不改变 `messages` 分页、排序或游标语义。
- gateway 契约 v1 不变。

## Web 行为

- `types/chat.ts` 的 `HistoryItem` 增加 `details?: unknown`。
- `toHistoryMessages` 在构造 tool 活动时调用 `parseCitationPassages(item.details)`，赋给 `passages`。
- `MessageBubble` 现有引用块、`passageId` 去重、展开交互不变；历史 tool 行与实时行走同一路径。
- 无 details / 解析失败 → `passages` 为空，保持现有工具卡，不报错。

## 验收

- 发送一次触发 `kb_search` 的消息 → 刷新页面/重新进入会话 → 引用块仍在，标题/段序号/页码正确，点击可展开。
- 同一轮重复引用按 `passageId` 去重。
- 旧消息（details 为 null）正常渲染，无引用块、无错误。
- 超 8 KiB 的 details 不落库（DB 为 NULL），历史无引用且日志有 WARN。
- `mvn verify`、web `typecheck`/`build`、浏览器验证（历史 mock 含 details）。

## Out of Scope

- 历史消息回填/迁移；旧会话不会获得引用。
- 引用点击跳回原文并高亮、按消息检索引用。
- 其它工具 details 的专门 UI（通用字段会存，但 UI 只消费 `passages`）。
