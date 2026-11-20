# 长期记忆由 gateway 提取、Java 版本化落库、before_agent_start 注入

Date: 2026-10-25

## Status

Accepted

## Context

FR-5 要求跨会话记住稳定事实。架构硬约束是 Java 永不直接调用 LLM、gateway 是唯一模型通路、pi 不做业务；同时用户要能查看/编辑/删除/关闭记忆，敏感凭证不得入库，冲突不能静默覆盖。

可选项包括：gateway 直接落库、Java 直接调 LLM、freeform 文本由 LLM 判断冲突、硬删除、在聊天里显示「已记住」。每条都会破坏边界、审计或用户体验。

## Decision

1. **gateway 提取，Java 落库**：`agent_end`（非重试）后，gateway 用当前会话模型异步提取结构化候选，去抖 60s，POST 到 Java `/api/profile/candidates`；Java 做敏感过滤、冲突消解与持久化。
2. **结构化事实 + 版本化**：`(user_id, category, fact_key)` 唯一 active；新值不同则旧行 `superseded`、新行 `supersedes_id=旧 id`；同值忽略。用户编辑只改展示文本/分类，删除为 `deleted` 软删。
3. **注入由 Java 推送**：每次 `ensureGatewaySession` 的 PUT body 带 active facts 与开关；gateway 用 per-session `profileRef` 在 `before_agent_start` 追加 system prompt。预算 20 条 / 4000 字符。
4. **敏感过滤双层**：提取 prompt 明确排除；Java 落库前关键词与密钥形态过滤，命中直接丢弃。
5. **聊天内静默**：提取与注入都不产生聊天气泡/toast；用户在设置页「长期记忆」查看与管理。
6. **提取 usage 不计入 FR-6 TPM**，内部候选回调不计用户 RPM。

## Considered Options(被否的备选)

- **Java 直接调用 LLM 提取** — 违反「gateway 是唯一模型通路」→ 否决。
- **gateway 直接写 `profile_facts`** — Node 服务持有业务库凭证、承担业务状态机，破坏 ADR-0006 边界 → 否决。
- **freeform 文本 + LLM 判断冲突** — 冲突结果不稳定、不可审计；结构化 key 可确定性版本化 → 否决。
- **硬删除** — 无法展示历史版本，也无法审计「为什么这条记忆消失了」→ 否决。
- **在聊天中显示「已记住」** — 打断对话、可能泄漏共享屏幕信息，且容易污染模型上下文 → 否决。
- **每次 run 都提取** — token 成本高；60s 去抖 + 转录上限已足够覆盖稳定事实 → 否决。

## Consequences

- gateway 多了一个异步模型调用与 Java 回调；失败只记日志，记忆是增强而非关键路径。
- 提取使用当前会话模型，成本计入用户 provider；FR-6 TPM 不统计提取/压缩摘要。
- 结构化 key 依赖提取 prompt 质量；非法 key/类别会被丢弃，用户可重新表述触发。
- 去抖是进程内状态；多实例可能重复提取，但唯一键与版本化保证不会出现两条 active 同 key。
- 关闭记忆保留既有事实；用户需要逐条删除或重新开启。
- 会话彻底删除不删除 profile facts（跨会话记忆），`source_session_id` 仅作来源记录。

## M1 实测与结论（2026-10-25）

用真实 DeepSeek `deepseek-chat` 对注入预算做了一次实测（system prompt 中追加 20 条格式化事实，`usage.prompt_tokens` 与无事实基线相减）：

| 场景 | 事实数 | 注入字符（含 `[category] ` 前缀） | 增量 tokens | 占 128K 窗口 |
|---|---|---|---|---|
| 典型 20 条（平均约 40 字） | 20 | 925 | 417 | 0.33% |
| 上限边界（20 条 × 约 200 字） | 20 | 4259 | 2189 | 1.71% |

结论：**维持 20 条 / 4000 字符**。即使取满字符预算，注入只占 128K 上下文窗口约 1.7%，远低于导致 compaction 提前触发的水平；典型配置约 0.3%。`injectionFacts` 的 4000 字符上限按 `fact_text` 计，`[category]` 前缀不在预算内（上限场景实测多出约 259 字符，约 140 tokens），已在上述测量中计入。

测量命令与样本见 `docs/tickets/m1-closeout/01-closeout-record.md`；需求 O2 据此关闭。
