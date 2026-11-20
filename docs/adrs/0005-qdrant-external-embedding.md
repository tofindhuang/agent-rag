# 向量库选 Qdrant，embedding/rerank 走可配置的 OpenAI 兼容端点

Date: 2026-10-23

## Status

Accepted（2026-10-23；实测见 `docs/research/2026-10-23-rag-selection-spike.md`）

## Context

FR-2 的 RAG 管线需要向量库、embedding 与 rerank。约束如下：

- `AGENTS.md` 规定 Java 永远不直接调用 LLM、gateway 是唯一模型通路；但 RAG 摄取在业务层，必须有人把 Passage 变成向量。
- 生成式模型凭证只存在于 pi 的凭证存储；业务层不应复制这套凭证。
- 需求草案列出的候选是 Qdrant / Milvus / pgvector；业务库是 MySQL 8（社区版无原生向量索引），Redis 已用于缓存与限流。
- 目标规模是单实例 50 并发活跃会话，M1 不需要为超大规模做预优化。
- 外部免费/低价 embedding 服务可用，但会限流、超时、停服；选型必须允许切换端点。

## Decision

- 向量库直接选 **Qdrant**，不做三选一 benchmark；payload 带 `user_id`、`document_id`、`seq`、`page`、`title`、`text`，对 `user_id` / `document_id` 建 payload 索引，检索用服务端过滤强制用户隔离。
- **2026-10-23 实施修订**：按 KB spec 的决策 7 改为**每用户独立 collection** `kb_passages_u{userId}`（懒创建、维度锁定），替代原「单 collection」方案；payload 中仍保留 `user_id` 并发起服务端过滤，作为纵深防御。
- embedding / rerank 走**可配置的 OpenAI 兼容 HTTP 端点**（默认 `/embeddings`、`/rerank`），可以是托管免费服务，也可以是本地 TEI；不兼容的服务用 adapter 适配。
- 业务层持有 encoder/reranker 的 endpoint 与 key；这把「生成式 LLM 凭证」和「encoder 推理凭证」分开，gateway 仍是唯一生成式模型通路。
- `EMBEDDING_DIM` 与 collection 维度锁死，启动时校验；更换 embedding 模型必须全量重建索引，并作为显式运维动作。
- rerank 默认开启：向量召回 **top-10** → rerank 取 top-5；可关闭，关闭时按向量分数返回并标记 `reranked: false`。top-10 是 spike 实测的延迟/成本折中：top-20 时端到端 p95 624ms，top-10 时 402ms。
- 用一次 spike 验证：100 页中文 PDF、300–500 个 Passage、20 条人工标注查询；记录 recall@5、MRR、摄取耗时、检索 p95、内存占用与成本；同时验证 429/超时重试、断点续跑和跨用户隔离。spike 已完成，结果见 `docs/research/2026-10-23-rag-selection-spike.md`。

## Considered Options(被否的备选)

- **gateway 增加 `/embed`、`/rerank` 端点** — 凭证集中、模型通路唯一，但把 gateway 拉进批量摄取热路径，需要自己做批处理、限流、重试；pi-ai 也没有 embedding 抽象，等于在 gateway 里新写一套 provider 集成。
- **强制本地 TEI，不允许外部端点** — 运维可控、无外部依赖，但失去免费/低价服务和快速切换模型的弹性；本决策保留 TEI 作为 endpoint 的一种实现。
- **Java 直接用生成式 LLM 的凭证调托管 embedding** — 最省事，但破坏「Java 不碰模型凭证」的边界，否决。
- **pgvector** — 需要额外运行 PostgreSQL，等于在 MySQL 之外再引入一种数据库引擎；运维与事务边界都变复杂。
- **Milvus** — 生产形态需要 etcd、MinIO 与多组件，M1 的 50 并发用不上，运维成本不成比例。
- **Redis Stack 向量检索** — 复用已有 Redis，但向量与缓存/限流共用实例，eviction 与持久化策略互相干扰，检索可用性被缓存绑架。
- **MySQL 8 社区版** — 没有原生向量索引（HeatWave 才有），不可行。

## Consequences

- 外部免费端点限流或停服时，摄取必须可重试、可断点续跑；检索失败要让 `kb_search` 返回明确错误，不静默降级为空结果。
- embedding 模型与维度是强绑定；换模型意味着全量重建，成本要在配置与运维文档中写明。
- Qdrant 多一个容器与备份策略；`docker-compose.yml` 中已有模板，需固定镜像版本。
- spike 结果：100 页/400 Passage 摄取 3.8s；top-10 时 recall@5 1.00、MRR 0.942、端到端 p95 402ms；跨用户隔离与重试/续跑/维度守卫通过。合成语料接近满分，rerank 未带来 recall 提升，真实文档质量需在 M1 复测。
- spike 未达到的假设：外部端点延迟会波动，top-20 在 60 次评测中 p95 624ms；因此默认候选数收紧为 10，后续如需更高召回可在真实语料上重新评估。
