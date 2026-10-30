# 检索拒答：绝对分数阈值 + 无答案评测样本

Date: 2026-10-29

## Status

Accepted

## Context

`kb_search` 目前没有任何相关性门槛：Qdrant 取候选池（默认 10 条）→ 可选 rerank → 取 top-5 **全量返回**。模型只看到 `score=0.xxx` 这个数字，从不会因为分数低而丢弃片段。结果是「知识库里没有这个问题」与「知识库里有相关内容」在协议上长得一样，模型倾向于硬凑片段回答（M2 待办里的「无答案拒答」）。

同时评测体系也表达不了「库里就是没有」：`kb_eval_labels` 的 `document_id/seq/passage_sha256` 全是 NOT NULL，每条标注必须指向一个 Passage；`kb_eval_items.status` 只有 `hit/miss/rotten/skipped`；`kb_eval_runs` 的指标列固定为 recall@5 / MRR@5 / 候选池 recall@10 / 直搜两列。没有无答案样本，就没有任何可复算的方式校准或证明阈值。

另一个约束：两套分数尺度不同。向量分是 Qdrant 余弦相似度；rerank 分来自用户自选的 rerank 供应商（可能是 logits，也可能是 0–1 概率），换模型即失准。而 rerank 可以按用户开关，同一个数字在开关切换后会落到另一套尺度上。

## Decision

1. **判据用绝对阈值**，不做「top1 与 topN 间隔」这类相对判据：相对间隔在「整库都是低分垃圾」时照样很大，恰恰在需要拒答的场景失效，且用户无法理解；绝对阈值是唯一能被评测复算的判据。
2. **按排序来源分别配置**，`kb_user_settings` 增加两列 `rerank_min_score` / `vector_min_score`（均可空）。rerank 生效时只读前者，rerank 关闭时只读后者，避免开关切换后阈值作用到另一套尺度。
3. **默认不启用**（两列为 NULL）：升级后 `kb_search` 行为与今天字节级一致。拒答正确性依赖校准，不能拍默认值。
4. **拒答是显式协议信号**：`kb_search` 响应增加 `noAnswer`、`maxScore`、`threshold`；只有「检索跑过但最大分低于阈值」时 `noAnswer=true`，未绑库/零命中仍走原空结果路径。拒答时**不回传被过滤片段的正文**，只给数字，避免「判定不相关却仍把内容塞进上下文」。
5. **模型引导取最小充分集**：工具描述补「`noAnswer` 表示库里确实没有，应直接说明未找到，不要编造、也不要反复换措辞重试」；系统 prompt 补「知识库类回答必须基于返回片段，没有片段时明确说未找到」；拒答时工具结果文案改为中性说明。不做「必须先检索再回答」这类硬性流程约束。
6. **评测扩展而非另起一套**：`kb_eval_labels` 增加 `expectation ENUM('answer','no_answer')`（现有行默认 `answer`），并把三列目标字段改为可空；`kb_eval_items.status` 不改枚举——对无答案样本，`hit` = 正确拒答、`miss` = 误答（仍记录 `score_final`，用于看「差多少分」）。`kb_eval_runs` 增加 `no_answer_count`、`refusal_accuracy`、`threshold_used`、`threshold_source`。
7. **指标口径**：`recall@5`/`MRR@5`/候选池 recall 的分母只含 `answer` 标签；`refusal_accuracy` 只在存在无答案样本时计算，否则为 NULL（UI 显示「暂无无答案样本」），不用 0/1 冒充。
8. **不新增 Prometheus 指标**：只加结构化日志与 `tool_result.details`。FR-7 既有的「检索命中率」语义不变（仍是「有没有拿到片段」），拒答会让它下降，这是预期而非回归。

## Considered Options（被否的备选）

- **相对判据（top1 与 topN 间隔 / 相对中位数倍数）** — 空库或整库低质时照样给出高置信度；解释成本高 → 否决。
- **只让模型自己看分数判断** — 不可复算、不可验收，阈值无法校准 → 否决。
- **per-KB 阈值** — 语料差异大时更精确，但当前每用户只有一套 embedding/rerank（分数尺度本就是用户级），要新增表/API/UI，收益不足 → 推迟。
- **单列阈值作用于「最终排序分」** — rerank 开关一拨语义就变，用户无法理解 → 否决。
- **给 `kb_search` 加部署级默认阈值** — 与 per-user 配置两处打架 → 否决。
- **无答案样本单独建表 / 单跑一套评测** — 评测要读两张表、两套口径，「一次运行」含义含糊 → 否决。
- **新增 `refused/answered` item 状态** — 需要改 ENUM，且 `hit/miss` 的语义映射已经足够表达 → 否决。
- **新增 `kb_search_refused_total` 计数器并扩 admin 看板** — 观测面扩展成本高于本期收益 → 推迟。
- **自动扫描候选阈值做「一键校准」** — 需要先定义优化目标（召回损失 vs 误答率），且依赖真实样本量 → 推迟到下一轮。

## Consequences

- 阈值未配置时行为完全不变；配置后拒答会让 FR-7「检索命中率」下降，需要在验收记录与运营文档中写明。
- 换 embedding/rerank 模型后旧阈值可能失准，必须用评测重新校准；`kb_eval_runs.threshold_used/threshold_source` 让历史报告可复算、可对比。
- 无答案标注与有答案标注共用「每 KB 100 条」上限，不是两套配额。
- 旧评测 run 缺新列，UI 显示「—」；`refusal_accuracy` 的 delta 只与同样含该指标的 run 比较。
- 拒答只在「检索成功且分数不足」时触发；embedding/rerank/Qdrant 故障仍走既有错误码，不会被伪装成「没找到」。
- 真实语料 50+ 标注属于数据工作：本切片交付能力与流程，样本量由部署方在真实 KB 上补齐。
