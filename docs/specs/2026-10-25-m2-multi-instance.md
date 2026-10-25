# M2 切片 1：gateway 多实例与会话生命周期

日期：2026-10-25
状态：待实现
关联：`docs/requirements.md` §4/§7；ADR-0006/0008（修订）、ADR-0024、ADR-0025；`CONTEXT.md`

## Problem Statement

M1 的 gateway 是单实例：Java 只配置一个 `GATEWAY_URL`，pi session 以 JSONL 落在本地目录，`SessionPool` 在进程内持有全部 `AgentSession`，退出时直接释放，既没有排空也没有容量上限。结果是：

- 部署滚动更新时，正在生成的回复会被截断，`docs/requirements.md` §4 的「滚动更新不断流」无法验收；
- 单进程内存随被访问过的会话单调增长，没有空闲驱逐；
- 2000 注册用户规模下，本地 session 文件没有保留上限；
- 实例数量、会话归属、故障接管都没有可表达的机制。

本轮把 gateway 从「单进程」推进到「单机多实例、会话亲和、可排空、可归档」，同时不改变「pi session 是引擎上下文唯一来源」和「Java 不直接调用 LLM」这两条既有边界。

## Solution

- 单台主机运行多个 gateway 实例，共享同一个 `GATEWAY_SESSION_DIR`（宿主机 bind mount）。
- gateway 向 Redis 注册实例、心跳、容量与 draining 状态；Java 为新会话在未满实例中按 `sessionId` 哈希放置，并把 `sessionId → instanceId` 钉在路由表里。
- 每个会话由共享目录里的文件锁保证单写者；锁是归属的最终事实源，Redis 路由表只是加速与放置提示。
- 空闲会话释放内存、保留文件；单实例有软上限；超过上限返回可重试错误，由 Java 改投其他实例。
- 滚动更新采用排空：停止接新轮次，在飞轮次跑完，10 分钟宽限后退出；空闲会话可被 Java 迁到健康实例。
- 90 天未活动的会话压缩归档到对象存储（Java 代理字节流），下次 prompt 自动恢复；归档对象永久保留，用户永久删除时一并销毁。

## User Stories

1. 作为部署者，我要在同一台主机上运行多个 gateway 实例，以便单进程故障或发布时仍有实例接客。
2. 作为部署者，我要 gateway 启动后自动出现在实例列表里，以便 Java 不需要手工维护地址。
3. 作为部署者，我要 gateway 退出后自动从实例列表消失，以便 Java 不再向死实例发请求。
4. 作为部署者，我要滚动更新时旧实例先排空再退出，以便正在生成的回复不被截断。
5. 作为部署者，我要排空有可配置的宽限期，以便长回复与短回复都能按同一规则处理。
6. 作为部署者，我要超过宽限期的轮次被明确 abort 并落成 partial，以便账本状态可预期。
7. 作为部署者，我要单实例有会话上限，以便一个热点用户不会拖垮整台机器。
8. 作为部署者，我要空闲会话自动释放内存，以便 2000 个历史用户不会把进程撑爆。
9. 作为部署者，我要空闲会话释放后还能继续聊，以便驱逐对用户是无感的。
10. 作为部署者，我要 90 天未活动的会话归档到对象存储，以便本地磁盘有上界。
11. 作为部署者，我要归档对象永久保留，以便用户任何时候回来都能继续。
12. 作为部署者，我要归档失败时保留本地文件，以便不会出现「本地没了、归档也没成」。
13. 作为部署者，我要归档/恢复有结构化日志与账本，以便排查失败与统计容量。
14. 作为部署者，我要 Redis 故障时已驻留会话继续可用，以便限流/路由组件不会成为聊天单点。
15. 作为部署者，我要 Redis 故障时新会话得到明确的可重试错误，以便客户端知道稍后再试。
16. 作为用户，我要在滚动更新期间继续收到完整回复，以便发布不影响我正在进行的对话。
17. 作为用户，我要在旧实例排空后仍能继续发消息，以便不需要手动刷新或重开会话。
18. 作为用户，我要很久没用的会话还能继续聊，以便历史对话不会因为时间过去而失效。
19. 作为用户，我要看历史时不触发引擎恢复，以便打开旧会话不会变慢。
20. 作为用户，我要归档后第一次继续聊时最多多等一次下载，以便可预期地恢复。
21. 作为用户，我要归档/恢复失败时得到可重试提示，而不是被静默开成一个空会话。
22. 作为用户，我要我的会话不会被其他用户打开或续写，以便多用户部署下没有串话。
23. 作为安全负责人，我要 sidecar 缺失时拒绝打开而不是自动重绑，以便归属元数据丢失不会变成越权。
24. 作为安全负责人，我要 gateway 不持有对象存储凭证，以便凭证边界仍然只在 Java。
25. 作为安全负责人，我要归档对象只经 Java 访问，以便桶不暴露给其他服务。
26. 作为开发者，我要刷新会话前能声明「它应该已有上下文」，以便 gateway 能拒绝静默新建。
27. 作为开发者，我要冲突错误里带持有者实例地址，以便 Java 能纠正过期路由并重试一次。
28. 作为开发者，我要文件锁在会话驻留期间一直持有，以便归档和另一个实例都不能同时写同一个 JSONL。
29. 作为开发者，我要驱逐、排空、归档共用同一把锁，以便三者不会互相踩踏。
30. 作为开发者，我要现有的 Java→gateway 调用都收敛到一个路由器，以便不需要在每个 service 里重复选实例。
31. 作为维护者，我要跨用户隔离矩阵测试，以便证明 A 打不到 B 的任何端点与上下文。
32. 作为维护者，我要 Redis kill、双实例并发打开、归档竞态等故障注入测试，以便这些语义不是纸面承诺。
33. 作为维护者，我要 5 实例 × 200 活跃会话的滚动更新压测，以便「不断流」有实测证据。
34. 作为维护者，我要单实例 60 会话的 RSS/CPU 基线，以便 16GB 主机能放几个实例有数据依据。
35. 作为维护者，我要归档账本记录失败次数与最后错误，以便不需要翻日志才能定位卡住的会话。

## Implementation Decisions

### 1. 拓扑与部署

- 单主机多实例，参考部署 5 个 gateway；共享宿主机目录作为 `GATEWAY_SESSION_DIR`，目录内是 `<sessionId>.jsonl`、`<sessionId>.meta.json` 与 `<sessionId>.lock/`。
- 目录权限 `0700`，gateway 以专用非 root 用户运行；备份与磁盘加密由部署方负责。
- k8s、多主机、网络文件系统、对象存储作为会话主存储均不在本轮；路由与排空机制不依赖具体编排。
- 实例需要稳定 `GATEWAY_INSTANCE_ID` 与可被 Java 访问的 `GATEWAY_PUBLIC_URL`；本地多进程用不同端口。

### 2. 实例注册与健康

- gateway 启动后向 Redis 写入 `gw:inst:{instanceId}`：`{url, startedAt, heartbeatAt, draining, sessions, maxSessions}`，TTL 15 秒，每 5 秒续约；同时把 ID 加入 `gw:instances` 集合。
- 优雅退出：先标记 `draining`，排空完成后删除 `gw:inst:{id}` 并从集合移除。
- `GET /health` 返回 `{ok, contractVersion, instanceId, draining, sessions, maxSessions}`；不暴露用户或会话内容。
- Redis 不可用时注册失败只记日志并重试，不阻止 gateway 接客；Java 用缓存与静态种子兜底。

### 3. Java 路由与降级

- 新增统一的会话路由器，所有会话级 gateway 调用（ensure、prompt、abort、release、delete、files、archive）都先解析实例。
- 放置：在存活、非 draining、`sessions < maxSessions` 的实例中按 `sessionId` 哈希选择；选中后写 `route:sess:{id}`（TTL 24 小时，访问时续期），后续钉住。哈希放置保证注册表计数滞后时并发创建仍然均匀。
- 已驻留会话不因实例增减而搬家；只有排空、实例失效、驱逐后重新放置时才改路由。
- 收到 `409 SESSION_BUSY` 且带持有者地址：更新路由，向持有者重试一次。
- 连接失败或 `503 GATEWAY_CAPACITY`：标记该实例不可用，改投下一个；仅在尚未收到任何 SSE 字节时重试，同一 `turnId`。
- Redis 故障：使用本地缓存的实例列表 + `GATEWAY_URLS` 静态种子；会话级请求仍可经文件锁落到正确实例；无法确认可用实例时返回可重试错误，不静默落到单实例默认地址。
- Java 在 prompt 前判断会话是否已有消息/run，并把它作为 `requireExisting` 传给 gateway。

### 4. 文件锁与归属

- 锁目录原子创建；持有者文件记录 `instanceId`、`pid`、`acquiredAt`、`heartbeatAt`。
- 心跳 5 秒，失效阈值 30 秒；接管前交叉校验实例注册表，实例仍存活则拒绝接管。
- 接管用原子 `rename` 旧锁目录再 `mkdir`；竞争失败者重新读取并重试，不允许两个实例同时认为自己持有锁。
- 锁在会话驻留期间持续持有；空闲驱逐、排空迁移、进程退出时释放。
- sidecar 缺失但 JSONL 存在：`409 SESSION_STATE_CORRUPT`，不自动重绑；由运维显式修复。
- sidecar 存在但 JSONL 不存在：视为「已创建、尚无消息」的合法空会话，除非 `requireExisting=true`。
- `requireExisting=true` 且本地无文件：先尝试归档恢复；无归档或校验失败返回 `409 SESSION_ARCHIVE_UNAVAILABLE`，绝不创建空会话。
- `PUT /sessions/:id` 对已驻留会话幂等返回；对未驻留会话先取锁再打开/恢复。

### 5. 容量与驱逐

- `GATEWAY_MAX_SESSIONS` 默认 60；新会话超过上限返回 `503 GATEWAY_CAPACITY`（可重试，带 `Retry-After`）。
- 驱逐扫描每 30 秒一次；`now - lastActiveAt > GATEWAY_IDLE_EVICT_MS`（默认 15 分钟）且无活动 Run 时释放内存、释放锁、删除路由。
- 驱逐不删除文件；下一次 ensure 重新取锁并从磁盘恢复。
- 驱逐不迁移在飞轮次；有活动 Run 的会话跳过。
- 容量基线是设计目标：上线前必须实测单实例 60 会话的 RSS/CPU；超标时下调上限或增加实例。

### 6. 排空与滚动更新

- `POST /admin/drain` 或 `SIGTERM` 触发；接口需要共享 token，返回 202。
- drain 开始后：先关闭准入闸门，再写注册表 `draining=true`；新 ensure/prompt 立即返回可重试的 `INSTANCE_DRAINING`。
- 准入闸门对已接纳请求计数；drain 等 `inflight=0` 且活动 Run 结束，再关闭监听并等待 `server.close` 回调。
- 已在执行的轮次继续，SSE 不断；宽限 `GATEWAY_DRAIN_GRACE_MS` 默认 10 分钟，超时后 abort 剩余轮次并按既有语义落成 `partial`。
- 排空期间收到空闲会话的新 prompt：返回 `409 INSTANCE_DRAINING`；Java 调 `POST /sessions/:id/release`（有活动 Run 时返回 409），释放成功后删路由、改投健康实例、用同一 `turnId` 重试。
- 宽限期结束：`disposeAll()`、关闭监听、退出；新实例启动后重新注册并参与放置。
- 进程崩溃 / `kill -9` / 主机掉电不在承诺内；锁在 30 秒后可接管，会话按已落盘轮次恢复，在飞轮次为 `partial`。

### 7. 会话归档与恢复

- Java 每日 03:30 扫描 `sessions.last_active_at < now - 90 天` 的 active/archived 会话，批量 50；`SESSION_ARCHIVE_AFTER_DAYS=0` 关闭。
- 调任一存活 gateway 的 `POST /sessions/:id/archive`；gateway 以非阻塞方式取文件锁，锁被占用则跳过；先 gzip JSONL、计算 sha256，再上传 JSONL 与 sidecar。
- Java 校验 sha256、写入对象存储、更新账本为 `archived`；gateway 只在两个对象都成功后删除本地 JSONL 与 sidecar 并释放锁。
- 恢复发生在 `PUT /sessions/:id`：本地无文件时向 Java 拉取两个对象，校验 sha256，写临时文件并原子 rename，再打开；恢复不删除归档对象。
- 对象键 `sessions/{userId}/{sessionId}/session.jsonl.gz` 与 `sessions/{userId}/{sessionId}/session.meta.json`；对象元数据带 sha256 与字节数。
- 归档/恢复失败记录到账本（`attempts`、`last_error`），本地文件保留；不阻塞用户聊天。
- 永久删除顺序：删归档对象（幂等）→ 通知 gateway 删本地文件 → 删 MySQL 行；任一步失败保留可重试状态。
- 归档对象永久保留，不配置生命周期过期。

### 8. 账本与迁移

新增 `session_archives` 表，作为本轮唯一 schema 变更：

```sql
session_archives (
  session_id     varchar(64) primary key,
  user_id        bigint       not null,
  object_prefix  varchar(255) not null,
  jsonl_sha256   char(64)     null,
  meta_sha256    char(64)     null,
  size_bytes     bigint       not null default 0,
  status         varchar(16)  not null,  -- archived | failed | deleted
  attempts       int          not null default 0,
  last_error     varchar(512) null,
  archived_at    datetime(6)  null,
  updated_at     datetime(6)  not null
)
```

迁移是 up-only，写成幂等守卫（`CREATE TABLE IF NOT EXISTS`），不含 down 文件。本地执行只跑这一条新 SQL，不重放历史迁移。

### 9. 契约与错误码

- `PUT /sessions/:id` 请求体新增可选 `requireExisting`（默认 false）。
- 新增 gateway 内部端点：`POST /sessions/:id/release`、`POST /sessions/:id/archive`、`POST /admin/drain`。
- `GET /health` 扩展字段（向后兼容）。
- 新增错误码：`SESSION_BUSY`（409，带 `owner` 地址）、`SESSION_STATE_CORRUPT`（409）、`INSTANCE_DRAINING`（409，可重试）、`GATEWAY_CAPACITY`（503，可重试）、`SESSION_ARCHIVE_UNAVAILABLE`（409，可重试）。
- Java 内部归档端点复用 ToolToken 鉴权与 `X-User-Id`；只接受 gateway 调用。
- 契约文档与共享 fixture 同步更新；新增字段保持可选，旧客户端仍可工作。

### 10. 配置

gateway：

- `GATEWAY_INSTANCE_ID`（默认 hostname:port）
- `GATEWAY_PUBLIC_URL`
- `GATEWAY_MAX_SESSIONS`（60）
- `GATEWAY_IDLE_EVICT_MS`（900000）
- `GATEWAY_DRAIN_GRACE_MS`（600000）
- `REDIS_URL`（实例注册与心跳；新增依赖精确锁版本）
- `GATEWAY_SESSION_DIR`（沿用 ADR-0008）

server：

- `GATEWAY_URLS`（静态种子，逗号分隔；`GATEWAY_URL` 保留为单实例兼容）
- `SESSION_ARCHIVE_AFTER_DAYS`（90；0 关闭）
- `SESSION_ARCHIVE_BATCH_SIZE`（50）
- `SESSION_ARCHIVE_CRON`（默认 `0 30 3 * * *`）

### 11. 可观测

- gateway 日志：实例注册/注销、锁接管、驱逐、drain 开始/结束、归档跳过/成功/失败；只记 ID 与数字，不记对话正文。
- Java 日志：归档批次、成功/失败数、恢复耗时、路由纠正次数。
- 账本 `session_archives` 是归档失败与容量的查询来源；不新增前端看板。
- 已有 FR-7 admin 看板不改；归档指标暂不进入看板。

### 12. 安全与残余边界

- 共享目录不是按用户隔离的文件系统；任一 gateway 进程可读全部会话文件，这是单机私有部署的信任边界。
- 用户级隔离仍由 Java 的 `findActiveOwned` 与 gateway 的 sidecar/`entry.userId` 双校验保证。
- gateway 只监听内网；`GATEWAY_API_TOKEN` 必须设置并定期轮换；`X-User-Id` 只在共享 token 之后被信任。
- 归档对象包含完整对话与工具输出；桶保持私有，只经 Java 访问；对象存储与备份加密由部署方负责。
- 不支持「宿主机管理员不可信」的威胁模型；按用户加密静态数据不在本轮。

## Testing Decisions

好测试只验证外部行为：给定目录状态、实例状态与请求，断言响应、文件与账本结果，不断言内部类结构。复用现有 gateway HTTP 测试、`FakeGateway`、MySQL 集成测试与一次性压测栈。

### 1. gateway 集成测试

- 并发向两个实例打开同一会话：一个成功，另一个 409 且带持有者；JSONL 不交错。
- sidecar 缺失 + JSONL 存在：409，不重绑。
- `requireExisting=true` + 无本地文件 + 无归档：409，不创建空文件。
- 空闲驱逐后再次 ensure：从磁盘恢复，上下文连续。
- 达到 `GATEWAY_MAX_SESSIONS`：新会话 503；已驻留会话仍可用。
- drain：新 Run 被拒，在飞 Run 完成，宽限期后剩余被 abort。
- 锁接管：旧实例心跳停止后被接管；实例仍存活时拒绝接管。

### 2. server 集成测试

- 路由器：两个 fake gateway，验证哈希放置、钉住、409 纠正、容量 503 改投、Redis 不可用降级。
- 跨用户隔离矩阵：A 对 B 的 sessionId 打 ensure/prompt/history/rename/archive/delete/files，全部 404/403；A 的上下文不含 B 的标记。
- 归档端点：sha256 校验、幂等重试、对象缺失、账本状态迁移。
- 永久删除：对象、本地文件、MySQL 行的顺序与失败重试。
- 定时任务：阈值筛选、批量上限、锁占用跳过、失败留账本。

### 3. 故障注入

- kill Redis：在飞轮次完成；新会话可重试失败；恢复后路由自动恢复。
- kill gateway（非排空）：在飞轮次 partial；30 秒后锁可接管；重发可继续。
- 归档过程中 prompt：归档放弃或等待，本地文件不消失。
- 恢复时归档损坏：明确失败，不新建空会话。

### 4. 滚动更新压测

- 扩展 `tools/loadtest`：5 个 gateway 进程、共享目录、Java 静态种子、200 活跃会话。
- 逐个 drain→等待→kill→重启，全程继续发消息。
- 判据：0 硬错误；在飞轮次在 10 分钟内全部 completed；消息投影不丢不重；新轮次全程可接入（容量满时的可重试 503 单独计数）。
- 记录单实例 60 会话的 RSS/CPU 与 5 实例总内存。

### 5. 回归基线

- 现有 gateway 测试、server 全量测试、契约 fixture 必须保持绿色。
- `dev` CI 六项任务继续保持全绿；压测与容量数据归档到 `docs/research/`。

## Out of Scope

- 多主机、k8s、网络文件系统、对象存储作为会话主存储。
- 半截 LLM 流迁移 / 跨实例续流。
- Java 多实例与 Java 自身的亲和路由（本轮 Java 仍单实例）。
- OAuth 登录、摘要正文可见、gVisor 评估、沙箱预热/容器复用、限流调优、检索评测扩到 50+、无答案拒答。
- 按用户加密会话静态数据、宿主机管理员不可信模型。
- 对象存储生命周期与归档对象自动过期。
- 归档/恢复的前端进度 UI（只要求可重试错误与可接受的等待）。
- 管理后台写操作。

## Further Notes

- 本轮完成后 M2 仍不算完成：OAuth、摘要可见、gVisor、沙箱预热、限流调优等属于后续切片，M2 closeout 需等这些切片完成。
- 「不断流」的承诺边界是排空式滚动更新；崩溃与强杀仍是 partial，这一点必须在验收记录与用户文档中写明。
- 单实例 60 会话是待验证目标；如果容量测试不达标，优先下调上限或增加实例，不改变路由与锁语义。
- `session_archives` 迁移在本地执行时只跑新增 SQL，并先向 owner 报备；不重放历史迁移、不生成 down 文件。
- ADR-0024/0025 是本 spec 的架构依据；实现中若发现需要改变排空、锁或归档语义，先改 ADR 再改代码。
