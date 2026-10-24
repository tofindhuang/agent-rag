# 工具结果 details 落库，历史消息恢复引用

Date: 2026-10-24

## Status

Accepted

## Context

契约 v1 加法 `tool_result.details` 让 `kb_search` 把 citations（passageId/title/seq/page/content/score）带给前端；但 Java 的消息投影只存 `content` 文本，`messages` 表没有 details 列。结果是引用只在当轮 SSE 中存在，刷新或翻历史就消失，用户明确要求恢复。

可选方案：

- 不持久化，历史无引用（现状，已否决）；
- 历史加载时重新执行 `kb_search` 重建引用：结果不确定、产生模型/检索成本，且文档删除后无法还原；
- 只存 citation 快照到独立表：结构清晰，但多一套 DTO/关联，未来其它工具 details 还要再改；
- 复用 `messages` 的 JSON 列存完整 details：与已有 `tool_calls JSON` 一致，加法且通用。

## Decision

- `messages` 增加 `details JSON NULL`（up-only、幂等迁移），只写 `role='tool'` 行；内容为 gateway 发来的 `tool_result.details` 原样 JSON。
- Java 侧上限 8 KiB（按序列化字节）：超过则**丢弃整个 details 存 NULL 并 WARN**，不截断（截断会破坏 JSON）。
- 历史接口 `MessageSummary` 增加 `details` 字段，返回解析后的 JSON 对象；旧行/非 tool 行为 `null`，web 自动降级为现有工具卡。
- 不做历史回填：旧消息没有原始 details，无法可靠重建；老会话保持无引用。
- 生命周期跟随 message 行：会话永久删除时随行删除，不新增清理任务；不上独立表、不做跨消息聚合。
- 前端复用现有 `parseCitationPassages`，历史与实时共用同一渲染与按 `passageId` 去重逻辑。

## Considered Options（被否的备选）

- **独立 `message_citations` 表** — 需要额外迁移、联表与快照语义，收益只有少量空间；当前 details 已被 8 KiB 上限约束。
- **只存 `passages` 数组** — 更省，但把契约字段写死在投影层；其它工具的 details 无法复用。
- **截断 JSON** — 会产生不可解析的 details，比“无引用”更糟。
- **历史重跑检索** — 非确定、成本高，文档删除后不可复现。

## Consequences

- 每条 `kb_search` 工具行多约 3–4 KiB；单会话历史体积略增。
- 老消息不会凭空出现引用；这是明确的降级而不是 bug。
- 历史接口是 Java↔web 的加法变更，不涉及 gateway 契约 v1。
- 迁移必须幂等（`information_schema` 守卫），符合仓库 up-only 约定。
