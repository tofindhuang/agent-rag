# 知识库 / RAG（FR-2）：摄取、检索与引用

日期：2026-10-23
状态：实现中（tickets 01–05 已完成；06–08 待做）
关联需求：FR-2（知识库 / RAG，P0）
关联 ADR：[0005](../adrs/0005-qdrant-external-embedding.md)（Qdrant + OpenAI 兼容 embedding/rerank）、[0006](../adrs/0006-persistence-ownership.md)、[0013](../adrs/0013-object-storage-for-kb-originals.md)（原文入对象存储）
上游 spec：[2026-10-23-requirements.md](../requirements.md)

## Problem Statement

产品定位是「对话级知识库」，但知识库能力目前只是骨架：

- gateway 已注册 `kb_search` 工具，业务层 `KbServiceImpl` 仍是 stub，直接抛 `NOT_IMPLEMENTED`；
- 没有上传入口、文档管理、摄取管线与向量检索；
- 聊天里无法展示「回答来自哪份文档的哪一段」。

M1 要交付一个可用闭环：上传文档 → 异步摄取 → 对话中检索 → 展示引用。

## Decisions（grilling 结论）

1. **切片边界（全链路一小刀）**：摄取后端、文档管理 UI、`kb_search` 真实检索、聊天引用展示；不做多知识库、OCR、定时抓取、共享权限。
2. **任务执行**：RabbitMQ 工作队列；摄取与删除使用独立路由键/队列（`ingest` / `delete`），MySQL 的 `kb_documents` 行仍是任务状态唯一真相源。
3. **一致性**：文档行内 outbox（`enqueued_at`）+ 每 30s reconciler 补发；消费者按状态幂等。
4. **重试**：`kb.ingest.q` 失败经 TTL 队列 5s/30s/2m 重试 3 次，最终 `failed` 并投 `kb.ingest.dlq`；瞬时错误重试、永久错误立即失败。
5. **格式与限额**：PDF / Markdown / TXT / DOCX / HTML URL；单文件 ≤20 MB、URL ≤5 MB 且 30s 超时、≤500 页或 ≤2000 chunk；不做 OCR。
6. **切分与引用**：按段落打包 400–600 字、10% 重叠；每个 Passage 带文档内 `seq`、可选 `page`；引用展示「《标题》· 第 N 段（第 P 页）」。
7. **凭证归属**：embedding/rerank 为**用户级设置**（AES-GCM 加密），未配置时回落部署 env；Qdrant 连接为部署级；每用户独立 collection（懒创建，维度锁定）。
8. **检索**：query embedding → Qdrant `user_id` 过滤 top-10 → rerank top-5；空结果与失败严格区分，不降级为空。
9. **UI**：新增 `/knowledge` 页（上传/列表/状态轮询/删除/重建），入口放账号菜单；引用走契约 `tool_result.details` 加法。
10. **设置校验**：保存前强制探测（断言维度）；改 embedding model/dim 将全部文档标记 `stale`，需「全部重建」，期间检索返回可读错误。维度变化时同步以新维度重建该用户 collection（旧向量作废），否则启动维度守卫会拒绝启动。
11. **配额**：单用户 200 文档 / 1 GB 原文，超限 `KB_QUOTA_EXCEEDED`；worker 3 线程，同一用户同时只处理 1 篇。
12. **原文存储**：S3 兼容对象存储（默认 Silo，MinIO 的社区维护 fork）存原文；MySQL 存元数据与切片文本；Qdrant 存向量。删除顺序：对象 → Qdrant → MySQL。
13. **验收**：AC1 100 页 PDF ≤5 分钟可检索；AC2 引用展示；AC3 跨用户隔离；故障/重试/DLQ/stale/配额均有测试。

## 数据模型（MySQL，Flyway up-only）

- `kb_documents`：`id CHAR(36)`、`user_id`、`title`、`source_type(upload|url)`、`source_url`、`original_key`、`original_sha256`、`mime`、`size_bytes`、`status(queued|processing|ready|failed|deleting|stale)`、`attempts`、`next_attempt_at`、`lease_until`、`enqueued_at`、`chunk_count`、`error_code`、`error_message`、`version`、`created_at/updated_at`
- `kb_passages`：`id BIGINT`、`document_id`、`user_id`、`seq`、`page`、`content`、`char_count`、`created_at`；`UNIQUE(document_id, seq)`
- `kb_user_settings`：`user_id` PK、embedding 的 `base_url/api_key_cipher/model/dim/path`、rerank 的 `enabled/base_url/api_key_cipher/model/path`、`updated_at`
- Qdrant：每用户 collection `kb_passages_u{userId}`（Cosine，维度来自用户配置或部署默认），payload `{user_id, document_id, seq, page, title, text}`，索引 `user_id/document_id`

## API

- `POST /api/kb/documents`：multipart `file` 或表单 `url`；202 `{id,status}`
- `GET /api/kb/documents`：当前用户文档列表（不含原文）
- `GET /api/kb/documents/{id}`：详情
- `POST /api/kb/documents/{id}/reingest`：用已存原文重建，202
- `POST /api/kb/documents/reingest-all`：stale 全量重建，202
- `DELETE /api/kb/documents/{id}`：202，异步删除（对象 → Qdrant → MySQL）
- `GET/PUT /api/kb/settings`：读取（key 只回显末四位）/替换；`POST /api/kb/settings/probe` 探测维度与延迟
- `POST /api/kb/search`：内部 TOOL 身份，返回 passages（含 `page`）

新增错误码：`KB_DISABLED`（409，`KB_ENABLED=false`）、`KB_NOT_CONFIGURED`（409）、`KB_QUOTA_EXCEEDED`（409）、`KB_INDEX_STALE`（409）、`KB_UNAVAILABLE`（503，retryable）、`KB_UNSUPPORTED_FORMAT`（400）、`KB_FILE_TOO_LARGE`（413）。

## 配置（环境变量）

- 队列：`RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VHOST`、队列名前缀（`KB_QUEUE_PREFIX`，默认 `kb`）
- Qdrant：`QDRANT_URL/QDRANT_API_KEY/QDRANT_COLLECTION_PREFIX`
- 对象存储：`S3_ENDPOINT/S3_ACCESS_KEY/S3_SECRET_KEY/S3_BUCKET/S3_REGION/S3_PATH_STYLE`
- 默认 embedding/rerank：`EMBEDDING_*`、`RERANK_*`（用户未配置时回落）
- 守卫与限额：`KB_ENABLED`、`KB_ALLOW_PRIVATE_ENDPOINTS`、`KB_MAX_DOCUMENTS_PER_USER`、`KB_MAX_BYTES_PER_USER`、`KB_WORKER_THREADS`

## Out of Scope

- OCR / 图片 PDF、网页定时抓取、文档版本树与自动去重
- 多知识库/工作区、跨用户共享与权限组
- 对象存储直传（presigned URL）、文档预览/下载
- 按天/按分钟上传限流、计费配额、管理员调额界面
- 引用跳转回原文并高亮、按消息追溯引用

## Acceptance

- 100 页中文 PDF：≤5 分钟 `ready`，`kb_search` 可命中
- 助手回答展示「《标题》· 第 N 段（第 P 页）」，可展开片段
- 跨用户 canary 文档不出现在他人检索结果
- embedding 429/超时按 5s/30s/2m 重试，3 次后 `failed` + DLQ；publisher 崩溃由 reconciler 补发
- 维度错配保存即 400；改模型后文档 `stale` 且检索返回 `KB_INDEX_STALE`；重建后恢复
- 删除后对象/Qdrant/MySQL 无残留；配额超限返回 `KB_QUOTA_EXCEEDED`
- server Testcontainers（MySQL/Redis/RabbitMQ/Qdrant/Silo）+ 假 embedding/rerank；gateway 契约测试覆盖 `tool_result.details`

## Risks

- 外部 embedding 端点限流/抖动：靠重试、DLQ 与批量 32 兜底，真实语料质量需复测
- 用户级端点引入 SSRF 面：Java 出站守卫与 gateway 同策略，DNS rebinding 仍是残余风险
- 每用户 collection 带来 collection 数量增长：单用户量级可控，后续可迁移到共享 collection + 同维度约束
- 引用依赖 `tool_result.details` 新字段：旧 gateway 降级为工具卡展示，不阻塞聊天
