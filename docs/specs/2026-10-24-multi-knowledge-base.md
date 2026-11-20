# 多知识库：管理、会话绑定与检索隔离

日期：2026-10-24
状态：已实现（tickets 16–19）
关联需求：FR-2（知识库 / RAG）
关联 ADR：[0016](../adrs/0016-multi-knowledge-base-scoping.md)
上游 spec：[2026-10-23-knowledge-base-rag.md](2026-10-23-knowledge-base-rag.md)

## Problem Statement

目前同一用户的所有对话共享一套文档与向量，`kb_search` 无法区分「工作资料」「个人笔记」等边界。需要让用户管理多个知识库，并让每个对话只检索它绑定的那个。

## Decisions

1. **单归属**：文档属于一个 KB；会话通过 `session_knowledge_bases` 绑定一个 KB（首版每会话一行，可解绑；复合主键为将来多选预留）。
2. **默认 KB**：迁移为每个已有用户创建「默认知识库」；新用户懒创建。默认库可重命名但不可删除。新建会话未指定时绑定默认 KB；可在创建时选择其他库，或创建后解绑/切换。旧 web/API 不带 kbId 时后端回落到默认 KB。
3. **检索隔离**：Qdrant 每用户一个 collection，payload 增加 `kb_id` 并建索引；搜索过滤 `user_id + kb_id`；无绑定会话返回空结果。切换 KB 只影响后续检索，历史消息/引用不重跑。
4. **旧向量**：迁移把现有文档标记 `stale`，要求按 KB 手动重建；不做缺省 kb_id 兼容，避免跨库泄漏。
5. **删除**：只能删除空 KB（无文档、无会话绑定）；非空返回 409；默认库不可删。
6. **配额**：按用户总量（200 文档 / 1 GB），不按 KB 拆分；每用户最多 20 个 KB，名称 1–100 字符、用户内唯一。
7. **引用**：Passage 增加 `kbId/kbName` 快照，引用展示库名；历史 details 保存快照。
8. **不做**：文档移动/复制、多选会话、共享 KB、按 KB 配额/模型。

## 数据模型（MySQL，Flyway up-only）

新增 `knowledge_bases`：

```sql
CREATE TABLE knowledge_bases (
  id CHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_kb_user_name (user_id, name),
  CONSTRAINT fk_kb_user FOREIGN KEY (user_id) REFERENCES users (id)
);
```

- `kb_documents` 增加 `kb_id CHAR(36) NOT NULL`，FK `knowledge_bases(id)`，索引 `(user_id, kb_id, created_at)`。
- `session_knowledge_bases`：`session_id CHAR(36)` + `kb_id CHAR(36)` 复合主键，FK 两表，索引 `kb_id`；首版每会话一行。
- 迁移：为 `kb_documents`/`sessions` 中出现的每个 user 创建「默认知识库」（UUID），回填文档与关联表；回填后把 `kb_documents.status` 统一置 `stale`（版本 +1）。
- 迁移必须幂等（`information_schema` 守卫 + `INSERT ... WHERE NOT EXISTS`），符合仓库 up-only 约定。

## API

### 知识库管理

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/kb/knowledge-bases` | 当前用户 KB 列表，含 `documentCount`、`isDefault` |
| `POST` | `/api/kb/knowledge-bases` | `{name}`，创建；名称重复 409 |
| `PATCH` | `/api/kb/knowledge-bases/{id}` | `{name}` 重命名 |
| `DELETE` | `/api/kb/knowledge-bases/{id}` | 仅空 KB；非空 409 |

- 跨用户访问一律 404。
- 默认 KB 不允许删除（即使为空），避免新会话无库可用。

### 文档

- `GET /api/kb/documents?kbId=`：按 KB 过滤；省略时使用默认 KB。
- `POST /api/kb/documents`：增加可选 `kbId`（multipart 表单字段/查询参数或 URL 表单）；省略默认 KB。
- `GET/POST/DELETE/reingest` 都校验文档属于当前用户且 KB 一致。
- `reingest-all?kbId=`：只重建该 KB 的 stale 文档（省略默认 KB）。

### 会话

- `POST /api/chat/sessions` 请求增加可选 `kbId`；省略时绑定默认 KB；创建时可选「不使用知识库」（前端创建后解绑或显式 noKb 标志）。
- `PUT /api/chat/sessions/{id}/kb` `{kbId}`：切换当前会话的 KB；`kbId=null` 表示解绑（检索返回空）；只影响后续检索。
- 会话列表/详情返回 `kbId` 与 `kbName`，供聊天页展示。

### 检索

- `ToolIdentity.sessionId` → 查 `session_knowledge_bases`；无绑定时返回 `passages: []`。
- Qdrant filter：`user_id` + `kb_id`；命中 payload 的 `kb_id` 与预期不一致时告警并丢弃。
- `KbSettingsService.testRetrieval` 使用默认 KB（无默认则第一个 KB），结果标注 KB 名称。

## 摄取

- `KbIngestionServiceImpl` 从文档行读取 `kb_id`，写入 Qdrant payload。
- `VectorPayload` 增加 `kbId`；`VectorStore.search` 增加 `kbId` 参数；`ensureCollection` 增加 `kb_id` payload 索引。
- 旧向量无 `kb_id`：迁移已把文档置 `stale`；重建前检索报 `KB_INDEX_STALE`。

## UI

- `/knowledge` 顶部 KB 选择器/管理：列表、创建、重命名、删除（空库）；当前 KB 的文档列表与上传。
- 新建会话弹窗提供 KB 下拉（默认预选默认库，可选「不使用知识库」）；聊天页 header 显示当前会话 KB，可切换/解绑。
- 文档行显示所属 KB（当前页可省略）；「全部重建」只作用于当前 KB。
- 引用展示「《标题》· 库名 · 第 N 段（第 P 页）」。

## 验收

- 同一用户建 A/B 两个 KB；A 上传文档，B 会话检索不到 A；切回 A 可检索。
- 跨用户 KB/文档/会话均不可见。
- 旧数据迁移后进入默认 KB，重建前检索 `KB_INDEX_STALE`，重建后恢复。
- 删除非空 KB 返回 409；默认 KB 不可删；空 KB 可删。
- 现有设置/上传/摄取/引用测试无回归；web typecheck/build；浏览器验证 KB 创建/切换/文档过滤。

## Out of Scope

- 一个会话同时检索多个 KB。
- 文档在 KB 间移动/复制、共享 KB、权限组。
- 按 KB 配额、KB 级 embedding/rerank 设置。
- KB 级对象存储前缀（原文 key 仍按 user/document）。
