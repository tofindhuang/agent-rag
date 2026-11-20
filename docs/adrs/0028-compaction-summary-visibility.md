# 压缩摘要对用户可见：契约纯增量 + 独立投影表

Date: 2026-10-29

## Status

Accepted

## Context

pi 的 `compaction_end` 事件已经带 `CompactionResult.summary`（正文），但 gateway 的 `translateCompaction` 只透传 `reason/tokensBefore/tokensAfter`，摘要正文被整个丢弃。结果是 FR-4「摘要可见（P1）」无法交付：用户只看到上下文占比，以及一个由前端从 `context_usage` 下降 ≥15 个百分点**猜**出来的「已压缩」标记——刷新后不准、点不动、也看不到正文。

同时，Java 侧 `compaction_events` 表只服务 FR-7 的运营指标（次数、token 前后），而 FR-7 明确 admin 不可查看对话正文。摘要属于对话内容，因此**不能**并入 admin 读取路径。

可选项彼此牵连：

1. 摘要正文怎么进到 Web：契约增量透传 / gateway 新增读接口 / 直接读 pi session 文件；
2. 存哪：扩 `compaction_events` / `messages` 加 `kind` / 新建投影表；
3. 插在时间线的哪个位置：精确映射 pi 的 `firstKeptEntryId` / 用投影时刻的消息 id；
4. 上线前的存量会话要不要回填。

## Decision

1. **走契约纯增量**：`compaction` 事件数据新增可选 `summary`（仅当 `result` 存在且 `summary` 非空白时附带）。不做 gateway 读接口，不读 pi session 文件——摘要与消息投影同源，归档、恢复、多实例都不需要额外读路径。
2. **新建投影表 `session_compactions`**，不扩 `compaction_events`、不污染 `messages`。`compaction_events` 保持「只有指标没有正文」，admin 链路天然读不到摘要。
3. **锚点用投影时刻**：`after_message_id = 事件到达时该会话已投影的最大 messages.id`。不引入 pi entry uuid 映射——锚点只决定卡片插在哪，属于展示精度，不值得改消息投影 schema（且老数据永远补不精确）。
4. **权限沿用历史接口**：`GET /api/chat/sessions/{id}/compactions` 用 `findActiveOwned`，跨用户与归档会话一律 404；purge 时随会话级联删除。
5. **第 50 条上限、不做分页**：pi 的新摘要累积包含旧摘要（`prepareCompaction` 把 `previousSummary` 喂给 summarizer），最新一条才是生效摘要，翻更早的没有实际价值。
6. **回填存量会话：不做**。只记录上线后发生的压缩；老会话保持与今天一致的体验。
7. **顺手修掉假压缩事件**：`compaction_end` 在 `aborted` 或 `result` 缺失时不再发 `compaction` 事件（仍发 `context_usage`）。`compaction_events` 的计数口径因此变为「只统计成功压缩」。
8. **摘要正文原样存储**：不做凭证正则过滤（与 `messages.content` 同敏感级，本人可读、admin 不可读）；写入前 200,000 字符硬上限，超出截断并置 `truncated=1`。

## Considered Options（被否的备选）

- **gateway 新增 `GET /sessions/:id/compaction` 读接口** — 看一行已有数据要新建跨服务读路径，且绑定 gateway 在线、归档先恢复、多实例要路由 → 否决。
- **直接读 `GATEWAY_SESSION_DIR` 的 JSONL** — 破坏「pi session 是引擎上下文唯一来源、MySQL 是业务投影」的分工，跨主机/归档对象读不到 → 否决。
- **给 `compaction_events` 加 `summary` 列** — 该表被 admin 用量报表读取，正文会顺着 admin 链路泄露给只读观测角色 → 否决。
- **`messages` 加 `kind='compaction'` 行** — 时间线天然正确，但消息表语义被污染：投影逻辑、token 统计、导出都要排除新 kind，漏一处就是错数；且与 `compaction_events` 双写同一事件 → 否决。
- **精确锚定 `firstKeptEntryId`** — 需要 `messages` 携带 pi entry uuid，老数据无法回填，收益只是卡片位置更准 → 否决。
- **回填历史摘要** — 要跨多实例与归档对象遍历会话文件，判定「当前生效摘要」还需额外逻辑，而用户对新旧会话差异的容忍度远高于一次有误差的迁移 → 否决。
- **摘要落库前做凭证正则过滤** — 误伤代码片段，且给出「已脱敏」的错觉 → 否决。
- **摘要正文塞进 `GET .../messages` 响应** — 每翻一页重传整会话摘要 → 否决。

## Consequences

- 契约 v1 增加一个可选字段，属于向后兼容的纯增量；老 gateway 不发送时新 Java 不写新表、不报错。
- `compaction` 事件语义收紧为「一次成功的压缩」：aborted/失败的压缩不再出现在事件流、`compaction_events` 与「已压缩」提示里。若将来需要失败可见性，应新增独立事件而不是复用该类型。
- 摘要正文从此存在于 MySQL，属于对话内容级数据：需要跟随会话的归档、purge 与备份策略；admin 与运营看板永远不读它。
- 上线前的会话没有摘要行，UI 不显示任何「缺失」提示；需要时可用一次性脚本按本 ADR 的 schema 另行补做。
- 卡片位置基于投影时刻，overflow 在轮内压缩时会落在上一条已完成消息之后，可能比真实切点略早。
- 每会话最多返回最近 50 条压缩记录，不做分页；超出部分不展示。
