# 真实模型检索评测：bge-m3 + bge-reranker-v2-m3

日期：2026-10-25
关联：FR-2 AC4、[检索质量保障 spec](../specs/2026-10-25-kb-retrieval-quality.md)、[ADR-0022](../adrs/0022-kb-retrieval-quality-assurance.md)、[M1 closeout](../tickets/m1-closeout/01-closeout-record.md)
上游调研：[RAG 选型 spike](2026-10-23-rag-selection-spike.md)（该报告明确要求真实文档复测）
原始数据：`2026-10-25-real-eval-artifacts/`（标注、两次运行报告、汇总）

## 目的

1. 补齐 FR-2 AC4：用**真实文档 + 真实 embedding/rerank** 建立 ≥10 条标注，给出可复算的 recall@5 / MRR@5。
2. 对照合成语料 spike：真实文本上 rerank 是否仍然「没有收益」。
3. 记录真实供应商的 embedding / rerank 延迟，供生产容量评估参考。

## 环境与配置

| 项 | 值 |
|---|---|
| embedding | SiliconFlow `https://api.siliconflow.cn/v1`，`BAAI/bge-m3`，1024 维，batch 32 |
| rerank | SiliconFlow `https://api.siliconflow.cn/v1`，`BAAI/bge-reranker-v2-m3`，候选 10、top-5 |
| 向量库 | Qdrant `v1.14.1`（一次性容器） |
| 基础设施 | `tools/loadtest` 一次性栈（MySQL 8.4 / Redis / RabbitMQ / Silo），独立端口，未触碰开发库 |
| 服务 | 本地 Spring Boot，embedding/rerank 走部署默认（`source=system`），未创建用户级覆盖 |
| 凭证 | 仅通过进程环境变量传入；未写入仓库、报告或日志。该 key 已在对话中出现，建议用完后轮换 |

## 语料

三份**真实仓库文档**，经生产摄取管线解析、切分、真实 embedding 入库：

| 文档 | 切片数 | 说明 |
|---|---:|---|
| `docs/requirements.md` | 18 | 需求文档，含表格与里程碑 |
| `README.md` | 20 | 项目说明，含代码块与命令 |
| `docs/specs/2026-10-25-kb-retrieval-quality.md` | 32 | 本次新增 spec，含 SQL 与指标定义 |
| **合计** | **70** | 全部 `ready`，`BAAI/bge-m3` / 1024 维指纹已写入 `kb_documents` |

语料是真实项目文档（技术写作、结构化 Markdown），比 spike 的合成模板文本更接近生产，但仍不是扫描 PDF、表格密集型合同等最难形态。

## 标注方法

- 阅读每个切片后，为选中的切片撰写一个该切片能明确回答的问题，目标即该切片 `(document_id, seq)`。
- 共 **18 条**：spec 11 条、README 4 条、requirements 3 条；覆盖事实、数字、定义、流程与代码命令。
- 标注通过公开 API 创建，服务端解析 Passage 并写入文本指纹（`passage_sha256`），未手填哈希。
- **修正记录**：首轮把「风险 R1」标注到了 `requirements.md #18`，复核原文后确认 R1 在 `#17`，已通过同问题重标修正；下文的指标均基于修正后的两次运行。首轮运行仅保留在一次性的评测历史里，不作为证据。

## 结果

两次修正后的运行（`run-1.json` / `run-2.json`）**指标与逐条排名完全一致**：

| 指标 | 值 | 说明 |
|---|---:|---|
| 当前配置 recall@5 | **0.9444** | 17 / 18 |
| 当前配置 MRR@5 | **0.8889** | 16 / 18 |
| 候选池 recall@10 | **0.9444** | 17 / 18 |
| 向量直搜 recall@5 | 0.8333 | 15 / 18 |
| 向量直搜 MRR@5 | 0.6778 | — |
| rerank 增益 | **+0.1111 recall@5 / +0.2111 MRR@5** | 真实文本上 rerank 有实质收益 |

### 分文档

| 文档 | 标注 | recall@5 | 池 recall@10 | MRR@5 | 直搜 recall@5 | 直搜 MRR@5 |
|---|---:|---:|---:|---:|---:|---:|
| `requirements.md` | 3 | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| `README.md` | 4 | 1.0000 | 1.0000 | 0.8750 | 0.7500 | 0.5000 |
| `2026-10-25-kb-retrieval-quality.md` | 11 | 0.9091 | 0.9091 | 0.8636 | 0.8182 | 0.6545 |

### 逐条结果

| 文档 | 目标 | 问题 | 结果 | 向量排名 | 最终排名 |
|---|---|---|---:|---:|---:|
| requirements.md | #17 | 风险 R1 的内容是什么？ | hit | 1 | 1 |
| requirements.md | #16 | M1 MVP 的周期是多久？ | hit | 1 | 1 |
| requirements.md | #7 | FR-2 的 AC4 要求用真实文档建立至少多少条标注？ | hit | 1 | 1 |
| README.md | #17 | 需求文档在仓库中的位置是哪里？ | hit | 1 | 2 |
| README.md | #13 | JWT_SECRET 的长度要求是什么？ | hit | 2 | 1 |
| README.md | #10 | 构建沙箱镜像时使用哪个 Dockerfile？ | hit | 8 | 1 |
| README.md | #18 | README 路线图中 M2 计划包含哪些内容？ | hit | 2 | 1 |
| 2026-10-25-kb-retrieval-quality.md | #29 | 修改部署默认 embedding 模型后，检索会返回什么错误？ | hit | 5 | 1 |
| 2026-10-25-kb-retrieval-quality.md | #28 | 评测的内部并发数和单条查询预算分别是多少？ | hit | 1 | 1 |
| 2026-10-25-kb-retrieval-quality.md | #24 | 哪些接口不接索引闸门、只校验归属？ | hit | 1 | 1 |
| 2026-10-25-kb-retrieval-quality.md | #23 | 检索闸门 KbIndexGuard 的两个方法分别校验什么？ | hit | 1 | 1 |
| 2026-10-25-kb-retrieval-quality.md | #17 | 候选池 recall@10 很低时，说明问题主要出在哪里？ | hit | 1 | 1 |
| 2026-10-25-kb-retrieval-quality.md | #16 | recall@5 指标的定义是什么？ | hit | 1 | 2 |
| 2026-10-25-kb-retrieval-quality.md | #14 | rank_final 记录的是什么排名？ | hit | 2 | 1 |
| 2026-10-25-kb-retrieval-quality.md | #6 | 有效标注少于多少条时界面会提示样本过少？ | **miss** | — | — |
| 2026-10-25-kb-retrieval-quality.md | #5 | 每个知识库的评测运行历史保留多少次？ | hit | 7 | 1 |
| 2026-10-25-kb-retrieval-quality.md | #4 | 评测标注用什么来标识期望的 Passage？ | hit | 1 | 1 |
| 2026-10-25-kb-retrieval-quality.md | #2 | spike 评测的 recall@5 是在什么语料上得到的？ | hit | 2 | 1 |

## 判读

- **embedding 上限（池 recall@10 = 0.9444）**：17 / 18 的正确切片进入候选池，embedding 与切分整体可靠。
- **rerank 真实有效**：把 7 条从池内较低位提到第 1（`README #10` 8→1、`spec #5` 7→1、`spec #29` 5→1 等），把 2 条从第 1 降到第 2（仍在前 5）；最终 recall@5 与池 recall@10 相同（0.9444），说明**没有因 rerank 丢失前 5 命中**。MRR 从 0.6778 提升到 0.8889，排序质量改善明显。
- **唯一 miss 是 embedding 召回问题**：`spec #6` 的原文是「有效标注 < 5 条时标「样本过少」」，问题是「少于多少条……」，候选池 top-10 未包含该切片。这是措辞差异导致的召回失败，不是 rerank 问题；也印证了 spec 的判读规则「池 recall 低 → 先查 embedding/切分」。
- **与 spike 对比**：合成语料上 rerank 无 recall 增益、MRR 略降；真实文档上 rerank **+0.111 recall@5、+0.211 MRR@5**。合成语料的满分基线确实掩盖了 rerank 的价值，这与 spike 报告的限制说明一致。

## 真实延迟

`POST /api/kb/settings/test` 三次采样（同一次请求内 embedding + Qdrant + rerank）：

| 样本 | embedding | rerank |
|---|---:|---:|
| 1 | 120 ms | 206 ms |
| 2 | 107 ms | 169 ms |
| 3 | 119 ms | 138 ms |

18 条标注的同步评测总耗时：**1943 ms / 1732 ms**（并发 4，两次运行），平均约 0.10 s/条（含 embedding + 检索 + rerank）。

注意：这是单日、小样本、无 p95；供应商配额、限流与高峰期延迟需在生产配置下另行观测。

## 局限

- 18 条标注、一个 KB、三份文档、一对模型；样本量不足以给出置信区间。
- 问题是在阅读切片后撰写的，存在作者偏差；没有「无答案」查询与分数阈值场景。
- 语料是技术 Markdown，不含扫描 PDF、复杂表格、图片或超长文档。
- 延迟只测了三次且未覆盖配额/限流；评测历史里还包含标注修正前的运行，不作为指标证据。
- 模型版本由供应商托管，未来变更可能影响结果；复测应固定模型 ID 与日期。

## 复现

1. 起一次性栈：`cd tools/loadtest && docker compose up -d --wait`（独立端口，勿用开发库）。
2. 以环境变量配置 `EMBEDDING_BASE_URL/API_KEY/MODEL/DIM` 与 `RERANK_BASE_URL/API_KEY/MODEL`，启动 server（key 只进环境变量）。
3. 通过公开 API 上传三份文档，轮询到 `ready`；确认 `kb_documents.embedding_model = BAAI/bge-m3`、`embedding_dim = 1024`。
4. 拉取每个文档的 Passage，按 `labels.json` 的 18 条 `(query, document, seq)` 创建标注。
5. `POST /api/kb/retrieval/eval/run`；两次运行应得到 `run-1.json` / `run-2.json` 中的指标与排名。

## 结论

- **FR-2 AC4 达成**：真实文档 + 真实模型 + 18 条标注，recall@5 / MRR@5 可复算且两次一致；模型指纹已落库。
- **选型维持**：`BAAI/bge-m3` + `BAAI/bge-reranker-v2-m3`、候选 10 / top-5 在真实文档上表现良好，rerank 的收益首次得到实证。
- **后续建议**：把标注扩展到 50+ 条并覆盖生产语料形态（扫描件、表格）；补「无答案」查询；生产环境持续记录供应商延迟与配额；轮换本次临时使用的 API key。
