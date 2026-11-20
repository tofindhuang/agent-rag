# 多知识库：文档归属 KB，会话绑定单个 KB，Qdrant 按 kb_id 过滤

Date: 2026-10-24

## Status

Accepted

## Context

当前每个用户只有一套文档集合，所有对话的 `kb_search` 都检索同一批文档。用户要求「一个对话可以使用某个知识库」，而不是所有对话共用。需要在不复制整套 RAG 基础设施的前提下引入知识库边界。

约束：

- 现有文档、会话和 Qdrant 向量必须可迁移；
- 每个用户仍有 embedding/rerank 设置与配额，KB 不改变凭证归属；
- 检索必须继续按用户隔离，并新增按 KB 隔离；
- 首版每个会话只绑定一个 KB，不支持一次选择多个。

## Decision

- 新增 `knowledge_bases`（id/user_id/name/created_at/updated_at），每用户可有多个，名称在用户内唯一。
- `kb_documents.kb_id` 必填，文档只属于一个 KB；会话与 KB 用 `session_knowledge_bases` 关联表（首版每会话一行，复合主键为将来的多选预留空间）。
- 迁移时为每个已有文档/会话的用户创建「默认知识库」，把现有文档和会话归入；新用户首次访问时懒创建默认 KB。默认库可重命名（`is_default` 标记），但不可删除。
- 新建会话不传 `kbId` → 默认 KB；可在创建时选择其他库或创建后解绑。会话中途切换只影响后续检索，历史消息/引用不重跑。无 KB 的会话检索返回空。
- 检索：`kb_search` 从 `ToolIdentity.sessionId` 找关联表；无绑定返回空结果。Qdrant 仍按用户一个 collection，payload 增加 `kb_id` 并加索引，搜索同时过滤 `user_id` 与 `kb_id`。
- 现有 Qdrant 点没有 `kb_id`：迁移把所有文档标记 `stale`，要求按 KB 手动 `reingest-all` 重建；不做隐式“缺省即默认”的过滤，避免跨 KB 泄漏。
- 删除 KB 仅允许空 KB（无文档、无会话绑定）；有任一项返回 409；默认库永远不可删。
- 配额仍按用户总量（200 文档 / 1 GB），不按 KB 拆分；每用户最多 20 个 KB，名称 1–100 字符、用户内唯一。
- 引用增加 `kbId/kbName` 快照，历史引用不受后续改名/删除影响。
- 文档移动/复制、共享 KB、按 KB 配额/模型均不在首版。

## Considered Options（被否的备选）

- **每 KB 一个 Qdrant collection** — 隔离更强，但 collection 数量、维度守卫和迁移成本都高；首版用 payload 过滤。
- **会话多选 KB** — 检索合并、引用归属和 UI 都更复杂；首版单 KB。
- **文档可属于多个 KB** — 需要关联表与删除语义；首版单归属。
- **无迁移，老数据留在“无 KB”** — 旧会话检索会突然为空，体验不可接受。
- **Qdrant 缺省 kb_id 视为默认 KB** — 过滤条件复杂，且容易把旧向量错误暴露给新 KB。
- **配额按 KB** — 用户总量控制更简单，避免多 KB 绕过配额。

## Consequences

- 上线迁移后旧文档全部 `stale`，必须执行一次全量重建；期间检索报 `KB_INDEX_STALE`。
- Qdrant payload 与索引变更；`VectorPayload`/`VectorStore.search` 增加 `kbId`。
- 会话、文档、知识库三组 API 与 UI 需要同步；旧 web 调用不带 `kbId` 时后端回落到默认 KB。
- 删除 KB 不会级联删除文档，避免误删；用户需先清空。
