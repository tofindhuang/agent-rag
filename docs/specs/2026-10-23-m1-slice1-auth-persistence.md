# M1 第一刀：认证 + 持久化地基 + gateway 冷启动 + Redis 最近消息缓存

日期：2026-10-23
状态：已与用户逐问确认（Phase 1 grilling Q1–Q20）
关联 ADR：[0006](../adrs/0006-persistence-ownership.md)、[0007](../adrs/0007-jwt-access-rotating-refresh-auth.md)、[0008](../adrs/0008-gateway-cold-start-local-session-files.md)、[0009](../adrs/0009-flyway-mybatis-plus-persistence-stack.md)、[0004](../adrs/0004-normalized-gateway-contract.md)
关联需求：FR-1（会话 CRUD、历史持久化、冷启动恢复）、FR-6（认证、会话隔离）

## Problem Statement

M0 骨架能用，但没有身份也没有记忆：

- 任何人可以在请求体里随便填一个 `userId` 就冒充别人创建会话、发消息；
- 会话列表和消息历史只存在于浏览器内存和 gateway 进程内存，刷新页面、重启 Java 或重启 gateway，一切归零；
- gateway 用 `SessionManager.inMemory()`，进程重启后引擎上下文全丢，旧会话无法继续对话。

用户无法注册登录，聊过的话第二天就找不回来。

## Solution

交付一个完整的垂直切片：**注册 → 登录 → 建会话 → 流式聊天 → 刷新页面历史完整 → Java/gateway 重启后继续聊**。

三条存储边界清晰分工（与 ADR-0006 一致）：

- **MySQL**：用户、凭证、会话、消息投影、运行的唯一真相源；
- **pi 本地 session 文件**：引擎上下文的唯一来源（gateway 冷启动恢复用）；
- **Redis**：最近消息缓存，纯加速层，丢了只变慢、不丢数据。

## User Stories

1. As a 访客, I want 用邮箱和密码注册账号, so that 我拥有自己的会话与数据
2. As a 用户, I want 用邮箱密码登录, so that 系统能识别我并保护我的数据
3. As a 用户, I want 密码以 BCrypt 哈希存储, so that 数据库泄露也不暴露我的明文密码
4. As a 用户, I want access token 只存在页面内存、refresh token 放 httpOnly cookie, so that XSS 偷不走我的长期凭证
5. As a 用户, I want 页面刷新后自动用 cookie 换新 access token, so that 我不用反复登录
6. As a 用户, I want 登出后清 cookie 且 refresh token 被撤销, so that 这台设备不再能刷新我的身份
7. As a 用户, I want 有人重用我的旧 refresh token 时整族令牌被撤销, so that 被盗 token 无法继续作恶
8. As a 用户, I want 创建会话、看到我的会话列表（按最近活跃排序）, so that 我能管理自己的对话
9. As a 用户, I want 重命名会话, so that 我能按主题整理对话
10. As a 用户, I want 删除（归档）会话且它从列表消失, so that 界面保持干净，同时历史数据仍可审计
11. As a 用户, I want 新会话首轮消息后标题自动取消息前若干字, so that 不用手动起名
12. As a 用户, I want 发消息后流式收到回复, so that 体验即时
13. As a 用户, I want 中途刷新页面后已完成的回复与部分生成的内容仍在历史里, so that 流程中断不丢上下文
14. As a 用户, I want 翻看更早的历史（游标分页）, so that 长会话也能回看
15. As a 用户, I want 刷新页面后优先秒开最近消息, so that 常用路径不被数据库延迟拖慢
16. As a 用户, I want 用户 A 的任何请求都看不到用户 B 的会话与消息, so that 数据严格隔离
17. As a 运维, I want kill -9 gateway 后重启，活跃会话从 pi session 文件恢复并可继续对话, so that 单进程故障不终结所有会话
18. As a 运维, I want 重启后的会话仍校验归属（不能换个人接管）, so that 冷启动不成为越权入口
19. As a 运维, I want Java 重启后从 MySQL 恢复会话列表与历史, so that 业务侧无状态化
20. As a 运维, I want Redis 整个挂掉时历史查询自动回退 MySQL, so that 缓存故障不影响功能
21. As a 开发者, I want gateway 回调 Java 工具接口时带共享 token, so that 工具回调不是未鉴权入口
22. As a 开发者, I want 同一 turnId/事件重复投递不产生重复消息行, so that 网络重试与重启不制造脏数据
23. As a 开发者, I want 每轮执行的状态/错误/用量记录在 runs 表, so that 没有产出消息的失败轮次在历史里也有落点

## Implementation Decisions

### 认证（ADR-0007）

- 端点：`POST /api/auth/register`、`/login`、`/refresh`、`/logout` 公开；`GET /api/auth/me` 需 JWT。
- 注册：邮箱格式校验 + 小写归一化 + 唯一约束；密码最小 8 字节、最大 72 字节（BCrypt 输入上限）；BCrypt 哈希。
- Access token：JWT HS256，TTL 15 分钟，claims `sub=userId`、`jti`、`iat`、`exp`、`iss=agent-core`；密钥 `JWT_SECRET` 环境变量（≥32 字节，启动 fail-fast 校验）；只有 Java 验签，gateway 继续信任 `X-User-Id`。前端只存内存，经 `Authorization: Bearer` 携带。
- Refresh token：32 字节随机 base64url 不透明串，7 天有效期，`agentcore_refresh` httpOnly cookie（`SameSite=Lax`、`Path=/api/auth`、生产开 `Secure`、dev http 可关）；MySQL 只存 SHA-256 hash。
- 轮换：每次 `/refresh` 旧 token 标记 revoked、同 `family_id` 插入新 token；旧 token 再次出现判定重用 → 撤销整个 family，返回 401。
- 登出：撤销当前 refresh token + 清 cookie；不做 access denylist，接受最长 15 分钟的旧 token 窗口。
- `users.id` BIGINT 自增；JWT `sub` 为数字字符串。

### 端点鉴权分层

- 公开：auth 四个端点 + `GET /api/health`。
- JWT：`/api/auth/me` 与全部 `/api/chat/**`。
- 工具回调（gateway → Java）：`/api/kb/**`、`/api/exec` 验共享 `GATEWAY_API_TOKEN`（Bearer）；未配置时放行但启动告警（仅限 dev）；既有六个身份 header 仍逐一校验。
- CORS：`allowCredentials(true)`，origin 限本机开发域名；生产同源部署无需 CORS。

### 持久化栈（ADR-0009）

- 迁移：Flyway，版本化文件命名 `V<YYYYMMDDHHMMSS>__<slug>.up.sql`（`.up` 归入描述部分，Flyway 原生兼容）；**up-only，绝不生成 `.down.sql`**（全局铁律）。
- ORM：MyBatis-Plus，`dao` 层 `extends BaseMapper<T>` 承载简单 CRUD；消息投影幂等 upsert、历史游标分页、批量写入等关键 SQL 写 XML 显式声明；不引入 `IService`/`ServiceImpl`/Wrapper 链式查询/ActiveRecord；schema 归 Flyway 所有，框架不做任何 DDL。
- ID 策略：DB 自增列用 `IdType.AUTO`（users、messages、refresh_tokens）；外部生成的 UUID 用 `IdType.INPUT`（sessions、runs、turnId）。
- 软删不用 `@TableLogic` 自动改写，归档用显式 `status`/`deleted_at` 条件，保持 SQL 可见。

### Schema（五张表，utf8mb4、InnoDB、DATETIME(6) 存 UTC）

- `users`：id BIGINT 自增 PK；email 唯一小写；password_hash；settings JSON 可空（留给记忆开关）；created_at/updated_at。
- `refresh_tokens`：id BIGINT 自增；user_id FK；token_hash CHAR(64) 唯一（SHA-256 hex）；family_id CHAR(36)；expires_at；revoked_at 可空；created_at；索引 (user_id)。
- `sessions`：id CHAR(36) PK（= AgentSession id = pi session id，Java 生成）；user_id FK；title；status ENUM(active/archived)；last_active_at；deleted_at 可空；created_at/updated_at；索引 (user_id, last_active_at)。删除 = 置 archived + deleted_at，列表只出 active；彻底清除留后续切片。
- `messages`：id BIGINT 自增；session_id FK；turn_id CHAR(36)；ordinal INT（轮内 Java 分配投影序号：用户消息 0，其后每个持久化事件 +1）；role ENUM(user/assistant/tool)；content；thinking 可空；tool_calls JSON 可空；assistant 行 status ENUM(complete/partial/error)、user 行 delivery_status ENUM(pending/sent/failed)；run_id 可空 FK；assistant 行另存契约 `seqInTurn`；tokens_in/tokens_out 可空；created_at；**UNIQUE(session_id, turn_id, ordinal)** 为幂等键（ADR-0006）；索引 (session_id, id) 支撑游标。
- `runs`：id CHAR(36) PK 为 Java 在发送前预生成的执行记录 id；session_id FK；turn_id；gateway_run_id CHAR(36) 可空（`run_started` 回填）；status ENUM(running/completed/aborted/failed)；error_code/error_message 可空；tokens_in/tokens_out（本轮累计）；started_at；settled_at 可空。messages.run_id 引用 runs.id。

### 消息投影流程（逐事件短事务，ADR-0006 语义）

- 发送前：一个事务插入用户消息（ordinal 0、pending）+ runs 行（running），turnId 由 Java 生成。
- `message_end`：插入 assistant 行（`stopReason=error` 时为 error，否则 complete；tokens 取自事件）。
- `tool_result`：插入 tool 行（内容按 64KB 截断）。
- `run_settled`：更新 run 终态、用户消息 delivery_status=sent。
- 断流/客户端断开（合成 STREAM_INTERRUPTED）：把自上一条 `message_end` 以来累积的 delta flush 成 assistant `partial` 行；run 记 `aborted`。
- gateway 不可达或拒绝：用户消息置 failed、run 记 failed，错误码优先取网关 envelope；请求可带同一 `turnId` 重试（复用用户消息行，新开 run）。
- runs 行保证「零消息失败轮次」（如模型 429 直接终止）在历史里有落点。
- 历史排序按 messages.id 升序；会话 last_active_at 随轮更新。

### 历史 API 与 Redis 缓存

- `GET /api/chat/sessions`：当前用户 active 会话，last_active_at 倒序，游标分页（复合游标 last_active_at+id），默认页大小 50。
- `POST /api/chat/sessions` 创建；`PATCH /api/chat/sessions/{id}` 重命名；`DELETE /api/chat/sessions/{id}` 归档。
- `GET /api/chat/sessions/{id}/messages`：`beforeId`（messages.id）游标 + `limit`（默认 50），返回时间正序；所有查询按 user_id 过滤（会话归属校验）。
- Redis：ZSET `chat:session:{sessionId}:recent`，member = 消息投影 DTO 紧凑 JSON（含 id 保证唯一），score = messages.id；只存已定稿消息（user sent / assistant complete、partial / tool result）；保留最新 100 条、TTL 24h 滚动续期。
- 写序：MySQL 提交成功后 best-effort 写 Redis，失败仅告警；读序：首页（无 beforeId）先 Redis、miss 或 Redis 不可用回 MySQL；更早页直接 MySQL。Redis 与 MySQL 不设双真相源。

### gateway 冷启动（ADR-0008）

- `GATEWAY_SESSION_DIR` 指定本地目录（默认 `./data/sessions`）存 pi session JSONL；`PUT /sessions/:id`：按 id 查找，存在则 `open`（restored），不存在则 `create({id})`。
- sidecar `${id}.meta.json` 记录 `userId`，重启后仍能校验归属；session 文件在而 sidecar 缺失时按当前 `X-User-Id` 绑定并告警。
- Java 在每次 prompt 前发幂等 `PUT /sessions/:id`（单实例下成本可忽略，换取必然可恢复）。
- M1 不做 session 文件清理（无限保留），清理与多实例共享存储留 M2。

### 前端（web）

- 登录/注册页；auth context：进入页面先 `/api/auth/me`，401 则带 cookie 调 `/refresh` 换新 access token（仅存内存）；请求统一带 Bearer；API 401 时自动刷新重试一次，失败跳登录页。
- 侧边栏：会话列表 / 新建 / 切换 / 重命名 / 归档；进入页面选最近活跃会话，没有则新建。
- 标题：新会话默认「新会话」，首轮用户消息发送后自动截取前 20 字符。
- 布局遵循现有 web 分层（页面组合、HTTP 进 lib/api、SSE 进 lib/sse）。

### 隔离与身份传递

JWT 验签后 Java 取 userId，作为一切业务查询的过滤条件与发往 gateway 的 `X-User-Id`（契约 v1 不变）。

## Testing Decisions

- 好的测试只测外部行为：经 HTTP 端点进出，不探测 DAO/内部方法。
- **server**：`@SpringBootTest` + MockMvc + Testcontainers（MySQL 8 + Redis 7，单例容器复用），Flyway 对真库跑迁移。覆盖：注册校验/邮箱重复、登录错密码、刷新轮换、旧 token 重用 → family 撤销、登出、`/me`；会话 CRUD 与跨用户隔离；投影幂等（同 turnId/ordinal 重复写入不产生重复行）；断流 partial；历史分页；Redis 不可用回退 MySQL；标题自动生成。本地无 Docker 时 `disabledWithoutDocker` 跳过并说明，CI（GitHub runner 有 Docker）在 `mvn verify` 全量跑。
- **gateway**：延续 fake pool 测试模式；新增文件态 SessionManager 测试（临时目录）：create→重启恢复 roundtrip、sidecar 绑定、归属不匹配拒绝。契约 fixtures 不变。
- **web**：`npm run typecheck` + build；auth 流程以 server 集成测试为准，本刀不引入 web 专属测试框架。
- 测试缝沿用现有最高缝（HTTP），唯一新增基础设施是 Testcontainers，不新增业务测试缝。

## Out of Scope

- access token denylist / 登出立即失效（15 分钟窗口明确接受）
- 登录限流与 RPM/TPM 限流（FR-6 后续票）
- 邮箱验证、找回密码、OAuth、RBAC、多设备会话管理
- refresh_tokens 过期行清理任务、归档会话/pi session 文件的彻底清除
- gateway 多实例与共享 session 存储、会话迁移
- RAG 摄取、沙箱执行器、compaction 配置化、画像记忆、用量看板（runs 表只记账不展示）

## Further Notes

- 三条存储职责（MySQL 真相 / pi 文件引擎上下文 / Redis 加速）是本刀的骨架原则，任何新需求先归位再实现。
- Redis 缓存键、cookie 名、环境变量（`JWT_SECRET`、`GATEWAY_API_TOKEN`、`GATEWAY_SESSION_DIR`、`AUTH_COOKIE_SECURE`）为首刀约定，改动需同步更新本 spec 与 ADR。
- 认证可调项：`app.auth.access-ttl`（默认 15m）、`app.auth.refresh-ttl`（默认 7d）、`app.auth.cookie-secure`（默认 false；`prod` profile 下由 `application-prod.yml` 置 true）。
- 断连即 abort、partial 落库等流语义继承 ADR-0006，本 spec 不重复论证。
- M2 钩子：denylist、多实例共享存储、清理任务、限流都已在 ADR-0007/0008 的 Consequences 里挂账。
