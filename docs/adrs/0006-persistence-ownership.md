# 业务 MySQL 只由 Java 写，pi 会话存储是引擎上下文的唯一来源

Date: 2026-10-23

## Status

Accepted

## Context

需求草案说消息「由 gateway 事件流投影写入」MySQL，但没有定清楚两件事：

- 谁执行写入？gateway 直接写业务库会突破「pi 不做业务」的边界，也会把数据库凭证带进 Node 服务。
- 冷启动恢复时，引擎上下文从哪里来？如果从 MySQL 重放消息，就要重建 pi 的 session 结构、对齐 compaction 边界和 token 账，复杂度和出错面都很大。

同时，断连、中断、失败都会产生「没有完整终态」的轮次，投影必须有确定的状态语义，否则会出现用户消息在、回复永远 pending 的脏数据。

## Decision

- **只有 Java 写业务 MySQL**；gateway 不碰业务库。
- **pi 的 session 存储是引擎上下文的唯一来源**；MySQL 消息投影只服务展示、历史与统计，不用来重建 pi 上下文。
- 三个持久化单元：prompt 前的用户消息（`delivery_status = pending`）、每个 `message_end` 对应的 assistant 消息（`status = complete`）、每个 `tool_result`（role=tool，内容按 64KB 截断）。delta 与进度事件不落库。
- `turnId` 由 Java 生成并随 prompt 发送，是消息幂等键；唯一键为 `(session_id, turn_id, ordinal)`：ordinal 是 Java 分配的投影序号（用户消息 0，其后每个持久化事件递增），契约的 `seqInTurn` 另存 assistant 行。网络重试与 Java 重启不会产生重复行。
- 每轮另有一行 `runs` 账本（id 由 Java 在发送前预生成，gateway 的 runId 在 `run_started` 回填），承载零消息失败、终态错误与用量。
- 用户消息投递状态：`pending` → `sent`（收到 `run_started`）→ `failed`（gateway 不可达，允许同 `turnId` 重试）。assistant 消息状态：`complete` / `partial` / `error`。
- Java 的 SSE 读取循环结束但未收到 `run_settled` 时，合成 `STREAM_INTERRUPTED`，把已累积增量落成 `partial`，run 记为 `aborted`。
- 客户端断连按 abort 处理：Java 断开 upstream，gateway 杀本轮；已产生内容落成 `partial`，重连从消息投影读历史，不续流。

## Considered Options(被否的备选)

- **gateway 直接写 MySQL** — 写入时机最准，但 Node 服务持有业务库凭证、承担业务状态机，破坏「pi 不做业务」的边界，否决。
- **从 MySQL 投影重建 pi 上下文实现冷启动** — 看似只依赖一个存储，但需要重放消息、重建 compaction 边界和 token 账，且投影本身可能不完整；gateway 本地 pi session 存储已经是现成答案。
- **断连后 Java 继续 drain 到终态（后台跑完）** — 历史更完整，但用户已离开仍烧 token，且要维护无下游消费者的读取循环；当前选择省 token、行为可预期。
- **增量持久化每个 delta** — 断流时数据最完整，但写放大严重、消息语义破碎；按 `message_end` + 中断时 flush 部分缓冲已经够用。

## Consequences

- M2 多实例时需要决定 pi session 存储的共享方式（共享文件系统 / 对象存储 / 重放），这是 gateway 内部实现，契约不变。
- 消息投影可能与引擎上下文短暂不一致（例如投影写入失败），展示以投影为准、续聊以 pi session 为准；需要监控投影失败并允许补偿。
- 断连即 abort 会截断网络抖动中的回复，用户需重新生成；若产品改为「离开也跑完」，需要新 ADR 修改这条语义。
- 数据库只由 Java 访问，凭证边界清晰；审计与用量统计也必须由 Java 从契约事件投影，不能依赖 gateway 旁路写库。
