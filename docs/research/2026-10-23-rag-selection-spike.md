# RAG 选型 spike：Qdrant + SiliconFlow

日期：2026-10-23
关联：`docs/adrs/0005-qdrant-external-embedding.md`

## 结论

- **Qdrant + SiliconFlow 可用**：`BAAI/bge-m3`（1024 维）做 embedding，`BAAI/bge-reranker-v2-m3` 做 rerank；100 页中文 PDF 的 400 个 Passage 摄取耗时 **3.8 秒**，远低于 5 分钟目标。
- **rerank 候选数改为 10**：top-10 时 recall@5 = **1.00**、MRR = **0.942**、端到端 p95 = **402ms**；top-20 时 MRR 略高（0.958），但 p95 = **624ms**，超过 500ms 目标。top-10 是当前延迟/成本折中。
- 跨用户隔离、429/500/超时重试、断点续跑、维度守卫、检索失败明确报错全部通过。
- **重要限制**：语料是合成模板文本，检索基线很容易接近满分；rerank 没有带来 recall 提升，MRR 甚至略低。真实文档上的检索质量必须在 M1 用真实知识库复测。

## 环境与配置

| 项 | 值 |
|---|---|
| embedding | SiliconFlow `https://api.siliconflow.cn/v1`，`BAAI/bge-m3`，1024 维 |
| rerank | SiliconFlow `https://api.siliconflow.cn/v1`，`BAAI/bge-reranker-v2-m3` |
| 向量库 | Qdrant `v1.12.4`，单 collection `kb_passages_spike`，Cosine |
| embedding 批大小 | 32 |
| rerank 候选数 | top-10（对照实验：top-20） |
| 评测 | 20 条标注查询 × 3 轮 = 60 次检索 |

API key 只从环境变量读取，未写入仓库或报告。

## 语料与方法

- 用 `generate_corpus.py` 生成 **100 页中文 PDF**，共 **400 个 Passage**（每页 4 个），包含 8 个章节、10 类组件、6 类属性。
- 其中 380 个 Passage 属于 `user-a`，20 个「蓝鲸计划」相关 Passage 属于 `user-b`，用于跨租户隔离验证。
- 从 PDF 反向抽取 Passage 并校验 ID/页数，确认摄取源是 PDF 而不是内存数组。
- 20 条查询覆盖前 7 章（全部属于 `user-a`），每条有一个期望 Passage；另有一条 `user-a` 查询用于探测 `user-b` 的 canary 内容。
- 指标：
  - recall@5：期望 Passage 是否出现在前 5
  - MRR：期望 Passage 首次出现的排名倒数
  - 端到端 p95：查询 embedding + Qdrant top-10 + rerank 的总耗时

## 结果

### 检索质量与延迟（60 次评测）

| 指标 | 纯向量 top-5 | rerank top-20 | rerank top-10 |
|---|---:|---:|---:|
| recall@5 | 1.000 | 1.000 | 1.000 |
| MRR | 0.950 | 0.958 | 0.942 |
| 查询 embedding p95 | — | 214ms | 151ms |
| Qdrant 检索 p95（含查询 embedding） | 238ms | 238ms | 174ms |
| rerank p95 | — | 365ms | 225ms |
| 端到端 p95 | — | **624ms** | **402ms** |
| rerank tokens（60 次） | — | 78,126 | 39,033 |

结论：top-20 在 60 次评测中 p95 超目标；top-10 达标，且 rerank token 用量约减半。MRR 的 0.016 差异在合成语料上不足以作为选择 top-20 的理由。

### 摄取

| 指标 | 值 |
|---|---:|
| PDF 页数 | 100 |
| Passage 数 | 400 |
| 写入 Qdrant 点数 | 400 |
| 总摄取耗时 | 3,826ms |
| 单批（32 条）embedding p95 | 498ms |
| Qdrant 内存占用 | 约 119 MiB |

### 隔离与故障

| 检查 | 结果 |
|---|---|
| `user-a` 查询返回 `user-b` 数据 | 0 条泄漏 |
| `user-b` 数据量 | 20 |
| `user-b` canary 能被 `user-b` 自己检索到 | 是 |
| embedding 429 重试 | 通过（3 次尝试） |
| rerank 500 重试 | 通过（2 次尝试） |
| embedding 超时重试 | 通过（2 次尝试） |
| 断点续跑 | 通过（5/5 点） |
| 维度不匹配（embedding 512 vs 1024） | 拒绝 |
| collection 维度不匹配 | 拒绝 |
| embedding 持续 500 | 返回明确 `HTTP 500` 错误，不静默空结果 |

### 成本

本次评测（含摄取、60 次查询评测、隔离查询）累计：

- embedding：18,926 tokens
- rerank：39,033 tokens（top-10）

实际费用取决于 SiliconFlow 账户的免费额度与单价；spike 不把价格写死，M1 需要在用量统计里按控制台单价配置。

## 决策

1. 接受 ADR-0005：Qdrant + 可配置 OpenAI 兼容 embedding/rerank 端点。
2. 默认 rerank 候选数从 top-20 调整为 **top-10**，最终输出 top-5；配置项增加 `RERANK_CANDIDATES=10`。
3. `EMBEDDING_DIM=1024` 锁死；换 embedding 模型必须全量重建索引。
4. M1 需要在真实文档上复测 recall@5/MRR；如果真实语料上 rerank 仍无收益，再评估是否默认关闭 rerank 或换模型。

## 风险与后续

- **合成语料偏差**：模板化文本让纯向量检索接近满分，rerank 的价值被掩盖；真实文档必须复测。
- **外部端点延迟**：SiliconFlow 的 embedding/rerank p95 在 150–500ms 波动；免费端点可能更慢或限流。M1 需要查询缓存、超时与降级策略。
- **限流与停服**：重试和断点续跑已验证，但生产仍需监控失败率与队列长度。
- **维度锁死**：换模型 = 全量重建，需要在运维文档中写成显式动作。
- **成本**：token 计费随查询量和候选数增长；top-10 已减半，后续可按命中率再调。

## 复现

```bash
cd spike/rag-selection
/tmp/agent-core-spike-venv/bin/python generate_corpus.py /tmp/agent-core-rag-spike

export SILICONFLOW_API_KEY=...
RERANK_CANDIDATES=10 ROUNDS=3 node run-spike.mjs
```

结果写入 `/tmp/agent-core-rag-spike/metrics.json`。spike 代码只在 `spike/rag-selection` 分支，不进入 `dev` 主线。
