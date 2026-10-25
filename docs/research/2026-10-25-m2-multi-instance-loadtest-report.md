# M2 切片 1：多实例与滚动更新压测报告

日期：2026-10-25
环境：Apple M5 / 16 GB / macOS 26.6.2；一次性 Docker 栈（MySQL 8.4、Redis 7、Qdrant、RabbitMQ、Silo）+ 本地 Java/gateway 进程；真实 DeepSeek `deepseek-chat`；确定性假 embedding/rerank。
关联：`docs/specs/2026-10-25-m2-multi-instance.md`、ADR-0024、`docs/tickets/m2-multi-instance/09-rolling-update-loadtest.md`
原始数据：`docs/research/2026-10-25-m2-loadtest-artifacts/`

## 方法

- harness 只打 Java 公开 HTTP API（与 web 客户端同缝）；gateway 内部接口不直接调用。
- 5 个 gateway 进程共享 `GATEWAY_SESSION_DIR`；Java 通过 `GATEWAY_URLS` 与 Redis 注册表发现实例。
- 压测开始 5 秒后逐个 `POST /admin/drain`，等在飞轮次结束、进程退出、再启动新进程，验证滚动更新。
- 消息校验：每个 completed turn 必须恰好一条 user 消息、至少一条 assistant 消息，且投影文本与流式 `message_end` 一致。
- 一次性栈只使用 `agent-core-loadtest` 项目容器，不读写任何既有本地数据库。

## 结果一：200 活跃会话 × 5 实例 × 滚动重启

| 指标 | 结果 |
|---|---|
| 用户 / 轮次 | 200 / 1（180 chat、20 run_code） |
| 最终状态 | **200/200 completed** |
| 硬错误 | **0** |
| 本系统 429 / 上游 429 | 0 / 0 |
| 断流重试 | **20** 次，全部用同一 `turnId` 恢复 |
| 消息校验 | 200 sessions / 200 turns；missing、duplicates、roleMismatch、textMismatch、errors 均为 0 |
| 总时长 | 159.6s |
| chat 首 token P50/P95 | 3.4s / 12.7s（未扣除模型 TTFB；包含 provider 波动与排队） |
| chat turn P50/P95 | 7.5s / 16.9s |
| run_code 工具回传 | 13–26s（一次性容器冷启动 + 沙箱排队，不属于 M2 路由开销） |

滚动重启实际发生了 5 次 drain；最终所有在飞轮次完成，未出现硬错误或消息丢失/重复。20 次断流属于滚动窗口内客户端可重试中断，最终全部恢复；**本轮没有做到「零断流」**，只做到「零硬错误、零丢失、零重复、全部完成」。

## 结果二：单实例 60 活跃会话容量基线

| 指标 | 结果 |
|---|---|
| 用户 / 轮次 | 60 / 1（纯 chat） |
| 最终状态 | **60/60 completed** |
| 硬错误 | 0 |
| 总时长 | 6.1s |
| gateway 进程 RSS | 峰值约 **57.6 MB**，采样均值约 44.7 MB（采样 7 次，单进程） |

本机 16 GB 下，5 个实例各 60 会话的内存余量充足；瓶颈更可能出现在 provider 并发、Java 线程与沙箱，而不是 gateway 常驻内存。

## 压测暴露并修复的问题

1. **首轮 `requireExisting` 误判**：当前轮用户消息在 ensure 之前已投影，导致新会话被当成需要恢复归档。改为「存在更早 turn 的消息」才要求恢复。
2. **最低负载放置放大心跳滞后**：并发建会话时 Redis 计数最多滞后 5 秒，所有新会话堆到同一实例。改为在未满实例中按 sessionId 哈希放置。
3. **容量/排空错误被误判为归属冲突**：`SESSION_BUSY` 缺少 owner 时 Java 返回 `USER_MISMATCH`。改为可重试 `SESSION_BUSY`。
4. **健康实例被冷却级联**：容量满/排空也把实例标记 5 秒不可用，最终「全部不可用」。只有真实传输失败才惩罚；冷却期无健康候选时仍使用有容量实例。
5. **实例 ID 复用导致旧锁不可接管**：容器重启复用 instanceId，新进程被旧锁误判为「同实例存活」。锁 owner 增加进程世代 `incarnation`，只在世代一致时拒绝接管。
6. **排空实例锁未释放**：ensure 遇到 `INSTANCE_DRAINING`/`SESSION_BUSY` 时直接改投，未调用 release；现在先释放 owner 的空闲会话，活动 Run 则返回可重试错误。

## 结论

- 按 Q11 约定的判据：**0 硬错误、在飞轮次完成、消息不丢不重、新轮次可接入**——通过。
- 单实例 60 活跃会话容量基线达标；5 实例 × 200 会话可运行。
- 残余项：滚动窗口内仍有 20 次断流重试；若要追求「零断流」，需要进一步收紧 drain 与 Java 请求进入之间的竞态（例如 drain 前设置准入屏障并等待在途 ensure/prompt 计数归零）。
