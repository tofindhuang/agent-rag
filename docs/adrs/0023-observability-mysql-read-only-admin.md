# 观测与用量以 MySQL 为事实源，admin 只读角色由环境变量引导

Date: 2026-10-25

## Status

Accepted

## Context

FR-7 要求用量统计、全链路 `request_id` trace 与运营看板。仓库现状：

- `runs` / `messages` 已持久化 token；`exec_records` 已有 request_id、耗时与资源；`messages.details` 已存 `kb_search` 的结果。数据基本齐全，但没有聚合。
- compaction 事件只在前端展示；沙箱排队时间没有记录；`runs` 没有 request_id 与模型快照。
- 没有 admin 角色；Prometheus 只暴露限流指标。
- gateway 对用户自定义 provider 的模型成本硬编码为 0，`usage.cost` 在主路径不可用。
- M2 还要做多实例；产品数据需要一个跨实例共享的事实源。

产品形态选择是「产品内 admin 只读看板 + 用户自助用量」，而不是把运维数据推给外部时序库；管理后台的写操作（用户/模型/KB/配额/密钥）明确留给后续独立项目。

## Decision

1. **事实源是 MySQL**：用量与运营指标直接聚合 `runs` / `messages` / `exec_records`，只补 `runs.request_id/model_id`、`exec_records.queue_ms` 与 `compaction_events`。不建双写事件账本，不引入 Prometheus/时序库做产品聚合。
2. **admin 只读**：`users.role ENUM('user','admin')`，通过 `ADMIN_EMAILS` 环境变量在启动时提升存量用户、注册命中时直接建 admin；角色不进 JWT，每个 admin 请求按 `userId` 查库，撤权即时生效。admin 不看会话标题、正文、代码、stdout/stderr、密钥与 KB 内容。
3. **成本由服务端估算**：部署级价格表（每百万 token，按 `model_id`，含 input/output/cacheRead/cacheWrite）乘以 `runs` 的四类 token；缓存 token 单独持久化（`usage.input` 不含缓存）；缓存单价缺省时回落 input 价；未知模型价格显示「—」。M2 只算聊天模型。
4. **trace 用 DB 关联**：按 `request_id`（主）/`turn_id`/`run_id` 查询，返回 run、assistant 消息、工具调用与 `exec_records` 的时间线；不引入 OpenTelemetry/Jaeger。
5. **已知取舍**：彻底删除会话会删除其 runs/messages/exec/compaction 事件，因此历史用量会减少；不建抗删除的账本。

## Considered Options(被否的备选)

- **Prometheus + Grafana/时序库承载产品指标** — 基础设施指标合适，但按用户/会话聚合、成本估算、历史对比和字段级权限都很别扭；多实例还要额外聚合。
- **新建 append-only 用量账本** — 能抗会话删除、查询更干净，但引入双写与一致性负担；等出现账单/终身用量需求再做，看板 API 不变。
- **改 gateway 填 `usage.cost`** — 契约字段已有，但用户自定义 provider 仍无价格来源，且把定价逻辑推进 gateway；不解决根本问题。
- **JWT 携带 role** — 撤权要等 token 过期或刷新；按请求查库更直接，admin 请求量很小。
- **把管理写操作一起做** — 用户/模型/KB/配额/密钥的写操作会牵出审计、误操作与权限升级风险，必须另立 spec 与 Phase 1。
- **读操作落审计表** — 读多写少、量级大；M2 用 INFO 日志，写审计留给管理后台。
- **OpenTelemetry / Jaeger** — 标准化但要多一套采集与存储；当前 request_id 已贯通，DB 时间线足够。

## Consequences

- 用户「彻底删除」会话后，管理员与用户看到的历史用量/执行/压缩次数会下降；必须在文档与 UI 明示。
- 成本是估算：四类 token 分开计价，但缓存单价缺省时回落 input 价；未配置价格的模型必须显示不可用而不是 0。
- admin 只能看计数与元数据，不能用于内容审核；需要定位会话时依赖用户提供 sessionId。
- 多实例下指标天然一致（共用一个 MySQL）；Prometheus 继续只做限流等基础设施指标。
- 价格表在部署配置中，改价要重启；在线价目管理属于后续管理后台。
- 后续加用量账本、OTel、embedding/rerank 成本时，看板 API 与权限模型不需要推翻。
