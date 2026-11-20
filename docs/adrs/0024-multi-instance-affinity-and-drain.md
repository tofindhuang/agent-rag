# 多实例 gateway 用会话亲和路由、单写者文件锁与排空式滚动更新

Date: 2026-10-25

## Status

Accepted

## Context

M1 的 gateway 是单实例：pi session 以 JSONL 落在本地 `GATEWAY_SESSION_DIR`，`SessionPool` 在进程内持有 `AgentSession`，Java 只配置一个 `GATEWAY_URL`，`shutdown()` 直接关闭监听并释放所有会话。`docs/requirements.md` §4 要求「gateway 可横向扩展，会话按 hash 亲和路由」，并把「部署滚动更新不断流」明确延期到 M2。

这条验收项有三个硬约束：

- **pi session 是引擎上下文的唯一来源**（ADR-0006/0008），不能从 MySQL 投影重建；
- **同一个 JSONL 任何时刻只能有一个写者**，两个进程同时追加会把文件写坏；
- **LLM 流与 AgentSession 都在 gateway 进程内存里**，进程死亡时无法把半截流迁到另一台实例。

目标规模定为单机峰值 50–200 活跃会话、约 2000 注册用户；单机私有部署，不引入 k8s、网络文件系统或对象存储作为会话主存储。

## Decision

### 1. 拓扑

单台主机上运行多个 gateway 实例（参考 5 个），共同挂载同一个宿主机目录作为 `GATEWAY_SESSION_DIR`。本地文件系统保证文件锁语义可靠；k8s / 多主机不在本轮范围，但路由与排空机制不依赖具体编排。

### 2. 会话归属与路由

- gateway 启动时把 `instanceId → {url, draining, sessions, maxSessions, heartbeatAt}` 写入 Redis，每 5 秒续约，TTL 15 秒；优雅退出时删除注册。
- Java 为新会话在「存活、非 draining、未达上限」的实例中按 `sessionId` 哈希放置，随后把 `sessionId → instanceId` 写入 Redis 路由表并钉住；后续请求优先走路由表。哈希放置避免注册表心跳滞后时所有并发创建堆到同一实例。
- **文件锁是归属的最终事实源，Redis 路由表只是加速与放置提示。** 路由表过期或 Redis 故障时，Java 可以请求任意实例；实例发现锁被占用会返回 `409 SESSION_BUSY` 并带上持有者地址，Java 据此纠正路由后重试一次。
- Redis 不可用时：已驻留会话继续工作；Java 使用本地缓存的实例列表与静态 `GATEWAY_URLS` 种子；新会话仍由文件锁保证单写者，无法确认租约时 fail-closed 为可重试错误，不静默新建。

### 3. 单写者文件锁

每个会话在共享目录里有一个锁目录 `<sessionId>.lock/`，内含持有者 `instanceId`、`pid` 与 `heartbeatAt`。

- 获取：原子 `mkdir`；已存在则读取持有者，心跳超过 30 秒且实例已不在注册表时允许接管；接管通过原子 `rename` 旧锁目录后重新 `mkdir`，竞争失败者重试。
- 续约：持有期间每 5 秒更新 `heartbeatAt`；锁在会话驻留期间一直持有，而不是只在打开瞬间持有。
- 释放：空闲驱逐、排空迁移、进程退出时释放。
- sidecar `<sessionId>.meta.json` 缺失但 JSONL 存在时返回 `409 SESSION_STATE_CORRUPT`，**不再自动重绑归属**。
- Java 在 `PUT /sessions/:id` 中用 `requireExisting` 标记「这个会话应该已有引擎上下文」；gateway 在本地无文件且无归档时必须拒绝，绝不静默创建空会话。

### 4. 容量与驱逐

- 单实例软上限 `GATEWAY_MAX_SESSIONS`（默认 60）；超过上限时新会话返回 `503 GATEWAY_CAPACITY`（可重试），Java 改投其他实例。
- 空闲 `GATEWAY_IDLE_EVICT_MS`（默认 15 分钟）且没有活动 Run 的会话释放内存，保留文件与路由；下次访问从磁盘恢复。
- 不迁移正在执行的轮次；驱逐只针对空闲会话。

### 5. 排空与滚动更新

- 收到 `SIGTERM` 或显式 drain 请求后，实例先关闭准入闸门，再在注册表标记 `draining`；此后新的 ensure/prompt 立即失败为可重试的 `INSTANCE_DRAINING`。
- 已在执行的轮次继续跑，SSE 不断；宽限期 `GATEWAY_DRAIN_GRACE_MS`（默认 10 分钟），超时后 abort 剩余轮次并按既有语义落成 `partial`。
- 排空期间收到空闲会话的新 prompt：返回 `409 INSTANCE_DRAINING`，Java 调 `POST /sessions/:id/release` 释放内存与文件锁，删除路由，改投健康实例后用同一 `turnId` 重试。
- 准入闸门记录在途请求；drain 等 `inflight=0` 且活动 Run 结束，再关闭监听，并等待 `server.close` 回调后才退出。宽限期只覆盖异常长尾，正常滚动不应触发强制 abort。
- 新实例注册后重新参与放置。
- 进程崩溃、`kill -9`、主机掉电不在「不断流」承诺内：在飞轮次仍是 `partial`，锁在 30 秒后可被接管，会话文件按已落盘的轮次恢复。

### 6. 契约与错误码

新增/扩展 `PUT /sessions/:id` 的 `requireExisting`，新增实例内部端点 `POST /sessions/:id/release`、`POST /sessions/:id/archive`、`POST /admin/drain`，`GET /health` 暴露 `instanceId`、`draining`、`sessions`、`maxSessions`。新增错误码 `SESSION_BUSY`、`SESSION_STATE_CORRUPT`、`INSTANCE_DRAINING`、`GATEWAY_CAPACITY`；`SESSION_BUSY` 携带持有者实例地址。

## Considered Options(被否的备选)

- **L7 代理按 sessionId 一致性哈希** — Java 零改动，但多一个必须高可用的组件；代理不感知排空与容量，实例增减时哈希环变化会搬动活跃会话，仍要靠 gateway 冲突重试兜底。
- **任意实例都可打开同一会话（无亲和）** — 实现最简单，但直接违反单写者约束，JSONL 会交错损坏。
- **gateway 互相转发** — 请求打到哪台都行，由它转给持有者；SSE 多一跳，超时与断连语义复杂化，等于在 gateway 里再造代理。
- **只用 Redis 租约、不做文件锁** — 实例长时间卡顿后租约过期，恢复的旧实例仍可能继续写；本地共享目录已经提供了更硬的 OS 级锁，没有理由只信 Redis。
- **多主机 + 网络文件系统 / 对象存储作为会话主存储** — 为未来规模预留，但单机目标下引入锁语义不确定、原子写与缓存问题；本轮通过共享目录 + 文件锁解决，机制可复用，换存储后端时不动路由。
- **滚动更新时立即切断** — 实现最简单，但在飞回复会被截断，不满足 §4 的「不断流」。
- **滚动更新时迁移半截 LLM 流** — 需要重放 provider 流与重建 pi 运行态，复杂度和出错面都远超收益；明确不承诺。

## Consequences

- gateway 新增 Redis 依赖与实例注册/心跳逻辑；Redis 故障时进入「已驻留会话可用、新会话可重试失败」的降级态，需要监控与告警。
- Java 端新增路由服务、注册表读取、失败重试与迁移逻辑；`GatewayClient` 从单地址变为按会话寻址，所有会话级调用都必须经过路由器。
- 滚动更新最坏耗时约为「实例数 × 宽限期」；部署脚本/编排必须给出不小于宽限期的终止等待时间，否则排空会被外部强杀。
- 单主机仍是单故障域；主机故障时服务整体不可用，恢复依赖共享目录的持久化。
- 「单实例 60 活跃会话」是设计目标而非实测结论，上线前必须做容量基线；超标时降低上限或增加实例。
- 文件锁的失联接管依赖心跳与实例注册，进程长时间暂停（如 >30 秒）在理论上仍可能造成双写；通过缩短心跳、扩大失效阈值与注册表交叉校验把风险降到可接受，并在运维文档中记录。
- 需要新的跨用户隔离与故障注入测试；`docs/requirements.md` §4 的滚动更新验收需要改写为可测的排空语义。
