# 检索质量保障：模型指纹、查询调试与标注评测

日期：2026-10-25
状态：待实现
关联需求：FR-2（知识库 / RAG）
关联 ADR：[0022](../adrs/0022-kb-retrieval-quality-assurance.md)
上游 spec：[2026-10-23-knowledge-base-rag.md](2026-10-23-knowledge-base-rag.md)、[2026-10-24-multi-knowledge-base.md](2026-10-24-multi-knowledge-base.md)、[2026-10-24-kb-settings-management.md](2026-10-24-kb-settings-management.md)
调研依据：[2026-10-23-rag-selection-spike.md](../research/2026-10-23-rag-selection-spike.md)

## Problem Statement

现有保障只覆盖「管线没坏」：摄取计数校验、维度守卫、设置自检（固定查询）、spike 评测。三处缺口：

1. **真实文档的质量不可度量**。spike 的 recall@5 = 1.00 建立在合成模板语料上，报告已注明「真实文档必须复测」，至今未做。用户无法回答「我上传的这批文件，embedding 找得回来吗、rerank 排得对吗」。
2. **部署默认 embedding 绕过 stale 标记**。用户级设置变更会触发 `markAllStale` + `KB_INDEX_STALE` 闸门，但改环境变量里的部署默认值不经过设置接口，`source=system` 的用户会静默混用旧向量。
3. **rerank 效果不可见**。设置自检只报告「rerank 生效 + 延迟」，不报告它把命中排好了还是排坏了。

本 spec 把「上传文件的 embedding/rerank 质量」变成**可查询、可复现、可对比**的数字，并补上模型指纹闸门。

## 范围

**做**：

- 文档级 embedding 模型/维度指纹，检索前一致性校验（缺口 2）
- 自由查询调试接口：同一候选池给出「向量序」与「当前配置」双列结果
- 每 KB 的评测标注集（golden set）与同步评测运行，输出五项指标
- 运行历史持久化（每 KB 最近 20 次）与「与上次对比」
- 知识库页新增 `RetrievalPanel`

**不做**（明确非目标）：

- 自动门禁/CI 回归/告警（Q1 的 (c)，演进方向）
- 无答案查询评测（需要先有分数阈值机制）
- nDCG（标注是二值，退化且不直观）
- 跨 KB 评测集、共享 KB 的协作标注
- 标注的编辑历史/审计（删除后重标即可）
- 对 Qdrant payload 写模型字段（指纹落 MySQL，见 ADR-0022）

## Decisions

1. **归属**：评测标注属于 KB（`kb_id`），随 KB 级联删除；鉴权按 KB 所有者，跨用户一律 404。检索本身按会话绑定单 KB，跨 KB 评测集度量的是产品里不存在的检索形态。
2. **标注身份**：`(document_id, seq)` + 创建时 Passage 文本的 SHA-256 指纹。评测时重算指纹：Passage 行缺失或指纹不符 → 该标注 `rotten`，从指标中剔除并在报告中显式列出。重新摄取后同一 chunker 产出相同文本，指纹不变；chunker/解析器变化才腐化——这正是需要人知道的事。
3. **标注产生**：自由查询结果行上「标为期望答案」为主路径；Passage 查看器提供反向入口（输入问题 → 标该 Passage），消除「只能标注系统已召回内容」的偏差。每条标注单期望 Passage，`(kb_id, query)` 唯一，重复标注为覆盖。
4. **指标**：当前配置 recall@5 / MRR@5；向量候选池 recall@10（embedding 上限）；向量直搜 recall@5 / MRR@5（rerank 对照）。k 固定 5、候选池 10，与 `kb_search` 默认路径一致，不可调。
5. **持久化**：`kb_eval_runs`（汇总）+ `kb_eval_items`（明细）。每 KB 保留最近 20 次，插入新 run 时裁剪。`complete=false` 的 run 入库、UI 标注、**不作为「与上次对比」的基线**。
6. **执行**：同步 POST，服务端并发 4，总预算 60s。超预算时返回已完成部分并标 `complete=false`，未执行标注列入 `skipped`；指标只在已执行且非 rotten 的标注上计算。每 KB 标注上限 100 条。指标以 `DECIMAL(6,4)` 落库、UI 显示两位小数（避免浮点噪音，保留诊断精度）。
7. **界面**：新增 `RetrievalPanel`，位于 `DocumentList` 与 `SettingsPanel` 之间。内部两块：检索调试（输入 + 双列结果）与质量评测（标注管理、运行、指标卡、历史、可展开的逐条明细）。
8. **模型指纹**：`kb_documents.embedding_model` + `embedding_dim`，摄取 `ready` 时写入。检索/自由查询/评测前校验当前生效配置；不一致或 NULL（无法证明）→ `KB_INDEX_STALE`，消息区分「模型未知」与「模型已变更」，提示 `reingest-all`。
9. **触发与前置**：v1 仅手动触发；保存 embedding 设置后 UI 轻提示「建议重跑评测」。前置：KB 可用、无 stale、指纹匹配、至少 1 条有效标注；有文档处理中允许运行但显式警告；有效标注 < 5 条时标「样本过少，仅供参考」。
10. **实现结构**：抽出共享检索核心（候选池 → 可选 rerank），供 `kb_search`、自由查询、评测三处复用，避免三份超时/过滤/守卫逻辑漂移。

## 数据模型（MySQL，Flyway up-only）

新迁移 `V20261025120000__kb_document_embedding_fingerprint.up.sql`，必须幂等（`information_schema` 守卫 + `CREATE TABLE IF NOT EXISTS`），符合仓库 up-only 约定；不生成 down 文件。后续两张表各自一份迁移（`...130000` 标注、`...140000` 评测）。

### 文档指纹

```sql
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_documents'
      AND COLUMN_NAME = 'embedding_model'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE kb_documents
       ADD COLUMN embedding_model VARCHAR(255) NULL AFTER chunk_count,
       ADD COLUMN embedding_dim INT NULL AFTER embedding_model,
       ADD COLUMN embedded_at DATETIME(6) NULL AFTER embedding_dim',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE kb_documents
SET embedded_at = updated_at
WHERE @col_exists = 0
  AND embedded_at IS NULL
  AND status <> 'deleting';
```

- `embedded_at` 的语义是「该文档可能已有向量」，摄取 `ready` 时写入 `NOW(6)`；`markAllStale`/`requeue` 不清理它。它把「新上传尚未摄取」与「已摄取但指纹未知」区分开：前者不阻断检索，后者阻断。
- 存量行在迁移时回填 `embedded_at`、指纹保持 NULL：表示「无法证明向量出处」，检索被闸门阻断，直到 `reingest-all` 写入实值。这是有意的升级代价（见 ADR-0022）。

### 评测标注

```sql
CREATE TABLE IF NOT EXISTS kb_eval_labels (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    kb_id CHAR(36) NOT NULL,
    query VARCHAR(512) NOT NULL,
    document_id CHAR(36) NOT NULL,
    seq INT NOT NULL,
    passage_sha256 CHAR(64) COLLATE utf8mb4_0900_as_cs NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_eval_labels_query (kb_id, query),
    KEY idx_kb_eval_labels_kb (user_id, kb_id, created_at),
    CONSTRAINT fk_kb_eval_labels_kb FOREIGN KEY (kb_id)
        REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_eval_labels_document FOREIGN KEY (document_id)
        REFERENCES kb_documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_eval_labels_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- `passage_sha256` 是 `kb_passages.text` 原始字节的 SHA-256（passage 文本已归一化，直接对存储值求哈希）。
- 文档删除级联删除标注：指向已不存在 Passage 的标注没有价值。

### 评测运行

```sql
CREATE TABLE IF NOT EXISTS kb_eval_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    kb_id CHAR(36) NOT NULL,
    complete BOOLEAN NOT NULL,
    label_count INT NOT NULL,
    executed_count INT NOT NULL,
    rotten_count INT NOT NULL,
    skipped_count INT NOT NULL,
    embedding_model VARCHAR(255) NOT NULL,
    embedding_dim INT NOT NULL,
    rerank_model VARCHAR(255) NULL,
    duration_ms INT NOT NULL,
    recall5 DECIMAL(6,4) NULL,
    mrr5 DECIMAL(6,4) NULL,
    pool_recall10 DECIMAL(6,4) NULL,
    baseline_recall5 DECIMAL(6,4) NULL,
    baseline_mrr5 DECIMAL(6,4) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_kb_eval_runs_kb (user_id, kb_id, created_at),
    CONSTRAINT fk_kb_eval_runs_kb FOREIGN KEY (kb_id)
        REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_eval_runs_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS kb_eval_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    label_id BIGINT NOT NULL,
    status ENUM('hit', 'miss', 'rotten', 'skipped') NOT NULL,
    rank_final INT NULL,
    rank_vector INT NULL,
    score_final DOUBLE NULL,
    PRIMARY KEY (id),
    KEY idx_kb_eval_items_run (run_id),
    CONSTRAINT fk_kb_eval_items_run FOREIGN KEY (run_id)
        REFERENCES kb_eval_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_kb_eval_items_label FOREIGN KEY (label_id)
        REFERENCES kb_eval_labels (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- `rank_vector` 记录候选池内真实排名（1–10），`rank_final` 记录最终 top-5 内排名；未命中为 NULL。`status=hit` 等价于 `rank_final IS NOT NULL`，冗余存一份便于查询。
- 指标为 NULL 表示「没有可计算的有效标注」。

## 指标定义

对每次 run：标注集 L；`rotten` 与 `skipped` 剔除后得有效集 V，N = |V|。每条有效标注的期望 Passage 为 E = `(document_id, seq)`。每条查询执行与 `kb_search` 相同的路径（候选池 10、topK 5、用户 + KB 过滤）：

- `pool` = Qdrant top-10，按向量相似度降序。
- `rankVector` = E 在 pool 中的 1-based 位置（6–10 也记录，用于诊断）；不在池中为 NULL。
- `final` = rerank 启用时取 `rerank(pool, topK=5)`，否则取 `pool[0..5)`。`rankFinal` = E 在 final 中的 1-based 位置；不在为 NULL。
- `scoreFinal` = 命中行在 final 中的分数（rerank 分数或向量相似度）。

| 指标 | 公式 | 含义 |
|---|---|---|
| `recall5` | count(rankFinal ≠ NULL) / N | 当前配置下前 5 条是否含正确 Passage |
| `mrr5` | Σ(rankFinal ≠ NULL ? 1/rankFinal : 0) / N | 当前配置的排序质量 |
| `poolRecall10` | count(rankVector ≠ NULL) / N | embedding 上限：正确 Passage 是否被召回 |
| `baselineRecall5` | count(rankVector ≠ NULL ∧ rankVector ≤ 5) / N | rerank 关闭时（向量直搜）的 recall |
| `baselineMrr5` | Σ(rankVector ≠ NULL ∧ rankVector ≤ 5 ? 1/rankVector : 0) / N | 向量直搜的 MRR |

判读规则（写进 UI 提示）：`poolRecall10` 低 → 问题在 embedding/切分；`poolRecall10` 高而 `recall5` 低 → 问题在 rerank 或候选池排序。rerank 未启用时 `recall5 == baselineRecall5`、`mrr5 == baselineMrr5`，UI 注明「rerank 未启用」。

## API

全部走会话鉴权（`@AuthenticationPrincipal AuthenticatedUser`），KB 必须属于当前用户（否则 404）。路径前缀 `/api/kb/retrieval`。

### 自由查询

`POST /api/kb/retrieval/query`

```json
{ "kbId": "uuid", "query": "设备巡检周期是多少" }
```

响应（`Passage` 复用 `KbSearchResponse.Passage`）：

```json
{
  "query": "设备巡检周期是多少",
  "reranked": true,
  "rerankModel": "BAAI/bge-reranker-v2-m3",
  "current": [ { "passageId": "...", "documentId": "...", "seq": 7, "page": 3,
                 "title": "运维手册.pdf", "content": "...", "score": 0.87,
                 "reranked": true, "kbId": "...", "kbName": "默认知识库" } ],
  "vector": [ { "...": "同上，reranked=false，按向量序" } ]
}
```

- `current` 与 `vector` 均取 5 条；`vector` 是候选池前 5（与 `baselineRecall5` 的定义一致）。
- `reranked=false` 时 `current == vector`，`rerankModel=null`；前端显示单列 + 提示。
- 查询为空/超 512 字符 → 400。

### 标注

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/kb/retrieval/labels?kbId=` | 标注列表，含 `state: ok \| rotten`（读取时校验指纹） |
| `POST` | `/api/kb/retrieval/labels` | `{kbId, query, documentId, seq}`；服务端解析 Passage 并计算指纹；`(kb_id, query)` 冲突时覆盖目标；校验文档属于该 KB |
| `DELETE` | `/api/kb/retrieval/labels/{id}` | 删除；跨用户 404 |

- 第 101 条（不含覆盖）→ 409 `KB_EVAL_LABEL_LIMIT`。
- 目标 Passage 不存在 → 404；`query` 去首尾空白后长度 1–512。
- 创建/覆盖返回 `{id, query, documentId, seq, title, page, state, createdAt}`。

### 评测运行

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/kb/retrieval/eval/run` | `{kbId}`；同步执行，返回完整报告 |
| `GET` | `/api/kb/retrieval/eval/runs?kbId=&limit=` | 历史汇总，默认 20、上限 20，按时间倒序；不含明细 |
| `GET` | `/api/kb/retrieval/eval/runs/{runId}` | 单次报告含明细；跨用户 404 |

运行响应：

```json
{
  "runId": 12,
  "complete": false,
  "labelCount": 30, "executedCount": 28, "rottenCount": 1, "skippedCount": 1,
  "embeddingModel": "BAAI/bge-m3", "embeddingDim": 1024,
  "rerankModel": "BAAI/bge-reranker-v2-m3",
  "durationMs": 61000,
  "metrics": { "recall5": 0.8571, "mrr5": 0.7917, "poolRecall10": 1.0,
               "baselineRecall5": 0.7857, "baselineMrr5": 0.6429 },
  "items": [ { "labelId": 3, "query": "...", "documentId": "...", "seq": 7,
               "title": "...", "page": 3, "status": "hit",
               "rankFinal": 2, "rankVector": 1, "scoreFinal": 0.81 } ],
  "rotten": [ { "labelId": 9, "query": "...", "reason": "passage text changed" } ],
  "skipped": [ { "labelId": 21, "query": "..." } ]
}
```

- 无有效标注 → 400 `INVALID_REQUEST`（「没有可评测的标注」）。
- 指标只在 V 上计算；`N=0` 不会出现（已在上面拒绝）。
- 同步预算 60s；超时后不再发起新查询，剩余标注记 `skipped`，`complete=false`。

## 守卫与错误码

新增共享组件 `KbIndexGuard`（server），两个独立方法：

- `requireIndexUsable(userId)`：`KbAvailability.requireEnabled()` + `countStaleByUser == 0`。
- `requireFingerprint(userId, embedding)`：`kb_documents` 中 `status <> 'deleting'` 且 `embedded_at IS NOT NULL` 的行，若 `embedding_model IS NULL` 或与当前生效 `KbEmbeddingConfig.model()/dim()` 不等 → 抛 `KB_INDEX_STALE`。
  - 错误消息沿用模块既有英文风格（与 `kb_search` 现有错误一致）：NULL → 「some documents were ingested before embedding fingerprints existed; run reingest-all before searching」；不等 → 列出旧模型与当前 `model + dim`，末尾提示 `reingest-all`。
- 应用于：`kb_search`、`POST /retrieval/query`、`POST /eval/run`。标注列表与评测历史只校验归属：索引损坏时仍可查看与审计旧标注/旧运行，但不能再发起检索。
- 设置自检 `POST /settings/test` 保持现状（连通性冒烟，不做质量声明），不接闸门。

错误码：

| 码 | 场景 |
|---|---|
| `KB_INDEX_STALE`（已有） | stale 文档、指纹 NULL、模型/维度不一致 |
| `KB_EVAL_LABEL_LIMIT`（新增，409） | 标注数达 100 |
| `INVALID_REQUEST`（已有） | 空查询、超长、无有效标注 |
| `KB_UNAVAILABLE`（已有） | embedding/Qdrant/rerank 端点失败（沿用现有映射） |

## 摄取变更

- `KbDocumentDao.markReady` 增加 `embeddingModel`、`embeddingDim` 参数并写入两列；`KbIngestionServiceImpl` 从已解析的 `KbEmbeddingConfig` 传入。
- 摄取失败不写指纹（`failed` 不参与校验）。
- 既有 `markAllStale` 逻辑不变：用户级模型/维度变更仍先置 stale，再要求 `reingest-all`，重建时写入新指纹。

## 前端

新增 `web/components/knowledge/RetrievalPanel.tsx`，挂在 `KnowledgePage` 的 `DocumentList` 之后、`SettingsPanel` 之前，接收当前 `kbId`。类型进 `types/knowledge.ts`，HTTP 进 `lib/api/knowledge.ts`，状态进新 hook `hooks/useRetrievalQuality.ts`。

**检索调试**

- 输入框 + 「查询」；回车提交；空查询禁用按钮。
- 结果双列：左「向量序」、右「当前配置（rerank）」；每条显示标题、页码（无页码显示 seq）、分数 4 位小数、文本摘要（2 行截断）。
- 行内「标为期望答案」；已标注的同一问题在该行显示「已标注」并允许取消（删除）。
- rerank 未启用时合并为单列并提示「rerank 未启用，仅向量序」。
- Passage 查看器新增「标为期望答案」：弹小输入框填问题 → 调用同一创建接口。

**质量评测**

- 标注区：`count / 100`、列表（问题 + 目标文档/页码/seq + `ok|rotten` 徽标 + 删除）；rotten 行提示「目标 Passage 已变化，请重新标注」。
- 运行区：「运行评测」按钮；禁用条件 = 无知识库 / 无有效标注 / 有 stale；KB 被服务端禁用或配置缺失时由错误提示兜底；有处理中文档时警告「N 篇文档处理中，指标可能偏低」；有效标注 < 5 条警告「样本过少，仅供参考」。
- 结果区：五个指标卡 + `complete` 徽标（不完整时黄条说明 skipped 数）+ rotten/skipped 列表。
- 历史区：最近 20 次表格（时间、embedding 模型、rerank 模型、recall@5、MRR@5、候选池 recall@10、直搜 recall@5、与上一条完整 run 的 delta、完整徽标）；展开行显示与「上一条完整 run」的逐条变化（新命中 / 新丢失 / 仍丢失 / 过期待确认 / 未执行；「仍命中」视为无变化，只在全部无变化时提示）。
- 保存 embedding 设置成功后，`SettingsPanel` 显示「设置已保存；建议到检索评测重跑一次」的轻提示（不自动触发）。
- 所有错误经 `ApiError` 展示文案，不暴露堆栈或内部地址。

## 并发与预算

- 并发：固定 4 个工作线程（评测内部 `ExecutorService`），单条查询复用现有 `SEARCH_BUDGET`（20s）与各客户端超时。
- 总预算：60s，从请求进入开始计时；超时后把剩余标注记 `skipped`，`complete=false`。
- 上限：标注 100 条/KB；历史 20 次/KB。

## 验收标准

1. 上传并重建文档后，`kb_documents.embedding_model/embedding_dim/embedded_at` 有值；把部署默认模型改成另一模型（或直接改库模拟）→ `kb_search`、自由查询、评测均返回 409 `KB_INDEX_STALE`，消息指向 `reingest-all`；重建后恢复。
2. 升级后存量文档（指纹 NULL）检索被阻断，`reingest-all` 后恢复；`/settings/test` 仍可用。
3. 自由查询返回两列：`current` 与 `vector` 的前 5 条；rerank 启用时 `current` 顺序与同参数 `kb_search` 一致；rerank 关闭时两列相同且只有一列展示。
4. 标注：结果行点选创建成功；同问题再次点另一行覆盖；删除后计数减一；Passage 查看器反向标注成功；第 101 条返回 409 `KB_EVAL_LABEL_LIMIT`。
5. 评测：构造 10 条标注（含 1 条人为腐化）→ 报告 `executedCount=9`、`rottenCount=1`，指标手工可复算；腐化标注列在 `rotten`。
6. 预算：mock 慢端点使总耗时超 60s → `complete=false`，未执行标注列在 `skipped`，指标基于已执行集。
7. 历史：连续运行两次 → 列表两条，第二条显示与第一条的 delta；`complete=false` 的 run 不作为基线；第 21 次插入后最旧一条被裁剪且明细级联删除。
8. KB 删除后其标注与 run 全部级联消失；跨用户访问返回 404。
9. `mvn -q test`、`npm run typecheck`、`npm run build` 通过；新增单元/集成测试覆盖指标计算、指纹腐化、闸门、上限与预算。

## 测试计划（TDD 缝）

| 层 | 缝 | 用例 |
|---|---|---|
| 单元 | 指标计算器（纯函数） | 命中/未命中/部分命中、rotten/skipped 剔除、rerank 关闭退化、N=1 边界 |
| 单元 | 指纹校验 | 相同文本同哈希；重建后 seq 漂移 → rotten；文档删除 → 标注级联 |
| 集成 | `KbIndexGuard` | 用户改模型 → stale；部署默认变更 → NULL/不等阻断；reingest 后放行 |
| 集成 | 评测端点 | 用 `FakeEmbeddingServer` + `FakeRerankServer`（已有支持类）构造固定排序，断言五项指标与明细排名 |
| 集成 | 标注端点 | 上限、覆盖语义、跨 KB 文档拒绝、跨用户 404 |
| 集成 | 历史 | 保留 20 次、裁剪级联、complete 基线规则 |
| 前端 | `npm run typecheck` + `npm run build` | 双列渲染、rerank 关闭单列、指标 delta、错误态（web 暂无组件测试框架，以构建与手工验收兜底） |

## 后续（不在本期）

- 自动门禁：配置保存或上传批次结束后后台跑评测，回归超阈值提示/阻断（Q1 的 (c)）。
- 无答案查询与分数阈值：需要先定义拒答语义。
- 评测结果的跨 KB 汇总视图（产品形态确定后再做）。
- 若将来支持共享 KB，再设计协作标注与权限。
