# Java↔gateway 契约 v1 采用归一化领域事件

Date: 2026-10-23

## Status

Accepted

## Context

骨架阶段的 gateway 把 pi 的 `AgentSessionEvent` 原样作为 SSE 事件透传给 Java 和 web：前端直接判断 `message_update` / `assistantMessageEvent.text_delta`，Java 只做字节转发。这带来三个问题：

- pi 的内部事件结构成为跨服务 ABI。ADR-0001 已经因为 pi 家族版本漂移出过一次运行时事故，透传等于把同一风险扩散到 Java 和 web。
- 每轮没有唯一终态。pi 同时有 `agent_end`（可能带 `willRetry`）、`agent_settled` 和 gateway 自造的 `prompt_settled`，前端已经在写「流结束但没收到 settled 也算完成」的兜底。
- M1 的消息投影、审计、用量统计都需要 Java 理解事件语义；让业务层解析 pi 的全部事件类型和嵌套结构，成本和脆弱性都不可接受。

同时，契约还缺会话幂等、身份校验、显式中断和稳定错误码，这些是 M1 持久化与多租户的前置条件。

## Decision

gateway 内部把 pi 事件翻译成一组封闭的领域事件，Java 与 web 只依赖这组事件：

- 事件集：`run_started`、`thinking_delta`、`text_delta`、`tool_call_delta`、`tool_call`、`tool_progress`、`tool_result`、`message_end`、`compaction`、`retry`、`run_settled`。
- `run_settled` 是每轮唯一终态，成功、中断、失败都走它；发出后流关闭。
- 每个事件带 `runId` 和单调 `seq`，SSE `id` 为 `<runId>:<seq>`；不支持回放。
- 一个会话同时只允许一个 Run，第二个 prompt 返回 `409 RUN_IN_PROGRESS`，不排队。
- 会话生命周期幂等：`PUT /sessions/:id` 创建或复用；`DELETE` 幂等；身份统一走 `X-User-Id` header 并在每次操作校验归属。
- 显式 `POST /sessions/:id/abort` 是用户「停止」的协议动作；客户端断连仍按 ADR-0002 触发 abort 兜底。
- 工具回调注入 `X-User-Id` / `X-Session-Id` / `X-Turn-Id` / `X-Run-Id` / `X-Tool-Call-Id` / `X-Request-Id`，身份绝不放进模型可控的工具参数。
- `/health` 与 `run_started` 暴露 `contractVersion`，Java 启动时主版本不匹配即 fail fast。
- 契约只做加法演进；删字段或改语义升主版本。

规范性细节（事件 payload、SSE 帧、生命周期、身份 header、错误码、工具回调、fixture 格式）以 `contract/v1/README.md` 为唯一权威来源。

## Considered Options(被否的备选)

- **继续透传 pi 事件** — 零改动，但把 pi 内部 schema 变成跨服务 ABI，与 ADR-0001 的方向相反；每轮终态仍需调用方猜。
- **归一化同时附带原始 pi 事件** — 过渡方便，但原始字段会永久留在契约里，耦合只是藏深一层，且泄漏工具/供应商内部细节。
- **只给 pi 事件加一层字段适配（改名、裁剪）** — 仍然保留 pi 的事件粒度和终态语义，Java 仍要理解 `agent_end`/`willRetry`/`agent_settled` 的关系，没有真正解耦。
- **按请求头协商契约版本、运行期兼容多版本** — M1 只有单套部署，协商机制没有收益；主版本 fail fast 更简单。

## Consequences

- gateway 多一个事件翻译器和一套契约 fixture；每接一个 pi 新能力要显式扩展事件，漏字段的风险由 golden test 和原始事件日志兜住。
- Java/web 的 ABI 稳定，pi 升级只需改 gateway 与 fixture；真实模型冒烟从 CI 中移出，改为契约变更时手工执行。
- `thinking_delta` 与 `tool_call_delta` 会暴露模型推理和参数生成过程，UI 默认折叠；这是产品可见性换来的代价。
- `tool_progress` 在 v1 中定义但随 M1 的 `run_code` 流式端点落地；`kb_search` 不造阶段假进度。
- 「断连即 abort」意味着网络抖动会截断回复，已产生内容以 `partial` 状态落库；若未来要求「离开也跑完」，需要新的 ADR 改变这条语义。
