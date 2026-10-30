# 检索拒答与无答案评测（M2 切片 4）

日期：2026-10-29
状态：Phase 1 已完成（Q1–Q10 逐问确认），待实现
关联 ADR：[0029](../adrs/0029-kb-refusal-threshold-and-no-answer-eval.md)、[0022](../adrs/0022-kb-retrieval-quality-assurance.md)、[0005](../adrs/0005-qdrant-external-embedding.md)
关联需求：FR-2（知识库 / RAG）、FR-7（检索命中率口径）
涉及模块：server、web、gateway（工具描述与结果文案）、契约（`tool_result.details` 纯增量）

## Problem Statement

`kb_search` 没有相关性门槛：候选池 10 条 → 可选 rerank → 取 top-5 **全量返回**。模型只能看到 `score=0.xxx` 这个数字，从不因分数低而丢弃片段，因此「库里没有」与「库里有相关内容」在协议上长得一样，模型容易硬凑片段作答。

评测体系也表达不了「库里就是没有」：`kb_eval_labels` 的 `document_id/seq/passage_sha256` 全是 NOT NULL、每条标注必须指向一个 Passage；`kb_eval_items.status` 只有 `hit/miss/rotten/skipped`；`kb_eval_runs` 的指标列固定。没有无答案样本，阈值既无法校准、也无法证明有效。

另有两个尺度约束：向量分（Qdrant 余弦）与 rerank 分（供应商相关，可能为 logits）不可比；rerank 还能按用户开关，同一个数字在开关切换后会落到另一套尺度。

## Solution

- `kb_user_settings` 增加 `rerank_min_score` / `vector_min_score`（可空，默认 NULL = 不启用）。rerank 生效时只读前者，关闭时只读后者。
- 检索后若最高分低于对应阈值：`kb_search` 返回 `noAnswer=true`、空 `passages`、`maxScore`、`threshold`，不回传被过滤片段的正文；打一条 info 日志。
- gateway 工具描述与系统 prompt 补充「`noAnswer` 表示库里确实没有，应如实说明未找到、不要编造或反复重试」。
- 评测支持 `no_answer` 标注（同一张表、同一次运行）：`hit` = 正确拒答、`miss` = 误答；`recall@5`/`MRR@5` 只在有答案标签上计算；新增 `refusal_accuracy`；每次运行记录所用阈值与来源。
- 前端补四处：设置页两个阈值输入、评测面板无答案标注与拒答准确率卡、调试面板阈值判定、聊天工具卡「未命中」态。

## User Stories

1. As a 用户, I want 当知识库里确实没有相关内容时模型明确说「没找到」, so that 我不会拿到一段东拼西凑的假答案
2. As a 用户, I want 拒答时仍然可以正常继续对话, so that 一次没检索到不影响后续提问
3. As a 用户, I want 自己决定要不要启用拒答、阈值填多少, so that 不同语料可以按需调紧或放宽
4. As a 用户, I want rerank 开关切换后阈值语义不串味, so that 关掉 rerank 不会突然把好结果全拒掉
5. As a 用户, I want 设置页能填两个阈值且留空即不启用, so that 升级后我的行为不会莫名改变
6. As a 用户, I want 在调试面板看到「这条查询在当前阈值下会被拒答」, so that 我能安全地校准数字
7. As a 用户, I want 聊天里看到「知识库未找到相关内容」的轻提示, so that 我知道是没找到而不是模型抽风
8. As a 用户, I want 给一个问题标「库里没有答案」, so that 我能用真实样本验证拒答是否可靠
9. As a 用户, I want 评测报告告诉我拒答准确率, so that 我知道阈值调得是紧是松
10. As a 用户, I want 评测历史显示每次用的阈值, so that 两次报告的差异不会被误读成模型变化
11. As a 用户, I want 有答案标签的召回不受无答案样本稀释, so that 指标含义保持不变
12. As a 用户, I want 没有无答案样本时看到「暂无」而不是 0%, so that 不会误判成拒答全错
13. As a 开发者, I want 阈值未配置时行为字节级不变, so that 升级是安全的
14. As a 开发者, I want 分数尺度与阈值归属明确, so that 换模型后知道该重新校准而不是改代码
15. As a 开发者, I want 拒答只在检索成功后判定, so that 端点故障不会被伪装成「没找到」
16. As a 开发者, I want 一次运行只用一个阈值, so that 报告可复算
17. As a 评测者, I want 误答样本记录当时最高分, so that 我能看出「差多少分就答了」
18. As a 运维, I want 拒答有结构化日志, so that 排障时能回答「为什么这次没结果」
19. As a 运维, I want 既有检索命中率语义不变（拒答会让它下降）, so that 看板口径不需要改
20. As a 产品负责人, I want 本轮不做自动阈值推荐, so that 功能边界清晰、可验收

## Implementation Decisions

### 1. 配置（server）

`kb_user_settings` 增加两列：

```sql
rerank_min_score DECIMAL(8,6) NULL,
vector_min_score DECIMAL(8,6) NULL
```

- `NULL` = 不启用。
- 语义：rerank 实际生效（配置存在且 rerank 返回结果）时用 `rerank_min_score`；否则用 `vector_min_score`。
- 设置接口 `PUT /api/kb/settings` 与视图 `GET /api/kb/settings` 增加 `threshold: { rerankMinScore, vectorMinScore }`；与既有字段一样整体替换语义。
- 不提供部署级默认（`app.kb.*` 不加项）。

### 2. 检索判定（server）

`KbRetrievalCore.Outcome` 增加：

```java
boolean noAnswer, double maxScore, Double threshold, String thresholdSource
```

判定规则（仅在检索成功后执行）：

1. 若 `ranked` 为空 → 不判定（`noAnswer=false`，`maxScore=null`），保持既有空结果语义；
2. 解析阈值：`ranked.reranked` 为 true → `rerankMinScore`，否则 → `vectorMinScore`；两者皆 null → 不判定；
3. `maxScore = ranked.get(0).score()`；若 `maxScore < threshold` → `noAnswer=true` 且**清空 `ranked`**（调用方拿不到被过滤的正文）；
4. `thresholdSource` 为 `rerank` / `vector`。

- embedding/rerank/Qdrant 的故障路径不变，仍抛 `KB_UNAVAILABLE` / `KB_NOT_CONFIGURED`，不会被伪装成拒答。
- 该判定对 `kb_search`、调试查询、评测运行**同时生效**（同一套核心，避免三处漂移）。

### 3. 协议（server → gateway）

`KbSearchResponse` 扩展：

```json
{ "passages": [], "noAnswer": true, "maxScore": 0.042, "threshold": 0.15 }
```

- `noAnswer` 仅在「检索成功但最高分低于阈值」时为 true；未绑库 / 零命中仍返回原空数组，`noAnswer=false`。
- `maxScore`、`threshold` 在拒答时必填；正常命中时可省略（不改变既有响应形状）。

调试与自检响应同步增加 `noAnswer / maxScore / threshold / thresholdSource`。

### 4. gateway 引导（最小充分集）

1. `kb_search` 工具描述补一句：`noAnswer` 表示知识库中确实没有相关内容，应直接告知用户未找到，不要编造或用不同措辞反复重试。
2. 系统 prompt 补一句：回答知识库类问题必须基于 `kb_search` 返回的片段；没有片段时明确说明未找到，不要用常识冒充文档内容。
3. 拒答结果文案：`知识库中没有找到与该问题相关的内容，不要凭空回答。`
4. 命中文案、citation 解析与 `details.passages` 保持字节级不变；`details` 增量带 `noAnswer / maxScore / threshold`。

### 5. 评测（server）

**标注表**：

- `kb_eval_labels` 增加 `expectation ENUM('answer','no_answer') NOT NULL DEFAULT 'answer'`；
- `document_id` / `seq` / `passage_sha256` 改为可空；
- 应用层校验：`answer` 必须三者齐备；`no_answer` 必须三者皆空；
- 唯一键 `(kb_id, query)` 不变，因此「同一问题」在两类之间切换即覆盖；
- 每 KB 100 条上限共用（无答案也算一条）；
- `no_answer` 标签的 `state` 恒为 `ok`（无 Passage 可腐化）。

**运行**：

- `no_answer` 标签：跳过硬指纹校验，执行检索；`outcome.noAnswer()` 为 true → `hit`，否则 `miss`；`score_final = outcome.maxScore()`，`rank_*` 为 NULL。
- `answer` 标签：既有逻辑不变（阈值可能让原本命中的标签变成 miss，这是有意的校准信号）。
- `recall@5` / `MRR@5` / 候选池 recall / 直搜两项：分母只含 `answer` 标签的执行结果。
- `refusal_accuracy`：分母只含 `no_answer` 标签的执行结果；无样本时为 NULL。

**持久化**：`kb_eval_runs` 增加

```sql
no_answer_count INT NOT NULL DEFAULT 0,
refusal_accuracy DECIMAL(6,4) NULL,
threshold_used DECIMAL(8,6) NULL,
threshold_source VARCHAR(16) NULL
```

- `threshold_used/source` 记录本次运行实际生效的阈值（rerank / vector / null）。

### 6. 前端（web）

1. **知识库设置**：`拒答阈值` 区，两个数字输入（rerank / 向量），留空=不启用；提示「阈值作用于对应分数尺度；换模型后请重新评测校准」。
2. **评测面板**：标注表单增加类型选择（有答案 / 无答案）；无答案只需问题；列表显示类型徽标；指标区在 `no_answer_count > 0` 时显示 `拒答准确率` 卡，否则显示「暂无无答案样本」；历史表增加「阈值」列（含来源），缺失显示 `—`。
3. **检索调试面板**：对当前查询显示判定行——「阈值 0.150（rerank）· 最高分 0.042 → 会被拒答」或「通过」；阈值未启用时显示「未启用拒答阈值」。
4. **聊天工具卡**：`kb_search` 拒答时显示「知识库未找到相关内容」状态（复用现有工具卡，不新增弹窗）。

### 7. 迁移与兼容

- 全部 up-only、幂等（沿用 `information_schema` 守卫 + `PREPARE` 模式），无 down 文件。
- 现有标注行默认 `answer`，数据不变。
- 旧评测 run 缺新列：UI 显示 `—`；`refusal_accuracy` 的 delta 只与同样含该指标的 run 比较，不与 NULL 相减。
- 阈值未配置时，`kb_search`、评测、调试面板行为与今天一致（硬门槛）。

## Rejected Alternatives

见 ADR-0029「Considered Options」：相对判据、模型自判、per-KB 阈值、单列阈值、部署级默认、无答案独立表、新增 item 状态枚举、新增 Prometheus 计数器、自动阈值推荐。

## Out of Scope

- 自动阈值推荐 / 一键校准；
- per-KB 阈值与部署级默认阈值；
- 新的 Prometheus 指标与 admin 看板扩展；
- `kb_eval_items` 枚举扩展（`refused/answered`）；
- 多语言 / 多向量库的分数归一化；
- 真实语料的 50+ 标注内容生产（数据项，由部署方在真实 KB 上完成，本切片交付能力与流程）。

## Acceptance

1. **阈值未启用时行为不变**：`kb_search` 响应、`details`、评测指标与今天一致；既有 server/gateway 测试全绿。
2. **启用后拒答**：最高分低于阈值 → `noAnswer=true` + 空 `passages` + `maxScore/threshold` + 一条 info 日志；模型侧工具结果为「知识库中没有找到…」。
3. **不误拒**：最高分高于阈值 → 响应与今天一致（含 citation）。
4. **rerank 开关不串味**：rerank 关闭时只读 `vector_min_score`，开启时只读 `rerank_min_score`（单测覆盖）。
5. **故障不伪装**：embedding/rerank 失败仍返回 `KB_UNAVAILABLE`，`noAnswer` 保持 false。
6. **评测**：`no_answer` 标签正确判定 `hit/miss`；`recall@5` 分母不含无答案样本；`refusal_accuracy` 有样本才算、无样本为 NULL；run 记录 `threshold_used/source`。
7. **规模**：50 条标注（含无答案）一次运行在 60s 预算内完成（用可复现的 fake 栈实测并记录耗时）。
8. **UI**：设置页两阈值、评测面板无答案标注与拒答准确率卡、调试面板阈值判定、聊天工具卡未命中态均可用；`npm run typecheck` 与 `npm run build` 通过。
9. **真实 50+ 标注**：作为数据项由部署方在真实 KB 完成；验收记录写明「工程能力已交付、真实样本量待补」，不以假 embedding 指标冒充质量结论。

## Residual Risks

- 换 embedding/rerank 模型后旧阈值可能失准，必须重新校准；报告里已有 `threshold_used/source` 供对照。
- 阈值调得过紧会让 recall 下降（评测可见），过松则拒答失效；默认不启用避免升级突变。
- FR-7「检索命中率」会因拒答下降，属预期，需在运营文档中说明。
- 无答案标注的「确实没有」是人工判断，可能存在标注者与语料范围不一致的争议。
