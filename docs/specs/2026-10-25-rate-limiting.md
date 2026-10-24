# FR-6 限流（Rate Limiting）

## Problem Statement

业务层目前对所有已认证 API 没有请求频率或 token 消耗上限。单个用户可以：

- 高频调用 chat / KB / 模型探测 / 沙箱工具，耗尽共享的模型额度、embedding 配额与宿主机资源；
- 对登录/注册/刷新接口做撞库或凭证喷洒；
- 在 prompt 中连续触发工具循环，token 消耗没有边界。

FR-6 要求 per-user RPM/TPM 限流并返回 429 与重试窗口（AC3）；当前错误码、过滤器与前端都没有这条路径。

## Solution

- 用 Redis 固定 60s 窗口计数实现限流：`RateLimitService` 提供原子 `INCRBY + 首次 PEXPIRE`、读取当前窗口与 TTL。
- `RateLimitFilter` 在 JWT / ToolToken 认证之后执行：
  - 已认证用户 API（含 `/api/exec`、`/api/kb/search`）按 userId 计 RPM；
  - 登录/注册/刷新按 client IP 计 RPM；
  - 健康检查与 OPTIONS 不限。
- `POST /api/chat/sessions/{id}/messages` 在创建 run 行之前做 per-user TPM 预检查；每条 assistant `message_end` 增量记账。
- 超限统一返回 `429 RATE_LIMITED`，带 `Retry-After` 秒数；CORS 暴露该 header。
- 触发、放行、Redis fail-open 与耗时进入 Micrometer 指标，经独立管理端口的 `/actuator/prometheus` 暴露。
- 配置可调、可全局关闭；Redis 故障 fail-open，不影响聊天与 KB 可用性。

## User Stories

1. As a 用户, I want 我的正常聊天、KB 检索和沙箱调用不被别人的高频请求挤掉, so that 服务在多人使用时仍然稳定。
2. As a 用户, I want 超过请求频率时收到明确的 429 和重试时间, so that 我知道多久后可以再试。
3. As a 用户, I want 超过 token 额度时在提问阶段就被拦下, so that 不会产生半截消息或已扣费但无回复的轮次。
4. As a 用户, I want 长对话与工具循环在合理额度内继续工作, so that 正常使用不会被误伤。
5. As a 用户, I want 不同会话共享同一个 per-user 额度, so that 换会话不能绕过限制。
6. As a 用户, I want 被限流时输入内容不丢失, so that 等待后可以直接重发。
7. As a 用户, I want 登录/注册接口不会被暴力尝试拖垮, so that 账号更安全。
8. As a 安全负责人, I want 所有已认证 API 都经过 RPM 检查, so that 没有可绕过的旁路。
9. As a 安全负责人, I want 工具回调端点也按用户限流, so that 模型或用户不能刷沙箱与检索。
10. As a 安全负责人, I want 限流不信任客户端伪造的 `X-Forwarded-For`, so that IP 额度不能被轻易绕过。
11. As a 运维者, I want 通过配置调整 RPM/TPM/IP 额度与窗口, so that 不同部署可以按容量调参。
12. As a 运维者, I want 一键关闭限流, so that 事故时可以先恢复可用性再排查。
13. As a 运维者, I want Redis 故障时 fail-open 并留下告警指标, so that 限流组件不会成为单点。
14. As a 运维者, I want 看到拒绝数、放行数、fail-open 数与 Redis 检查耗时, so that 能判断额度是否合理。
15. As a 运维者, I want metrics 不带 userId/IP 标签, so that 指标基数不会爆炸。
16. As a 开发者, I want 固定窗口计数与 TTL 用一段 Lua 原子完成, so that 并发请求不会绕过额度。
17. As a 开发者, I want 429 在 SSE 开始之前返回, so that 客户端不会收到半截流。
18. As a 开发者, I want 限流测试用独立低额度 context, so that 现有测试不被 429 干扰。
19. As a 开发者, I want 工具白名单有回归测试, so that 模型不能调用 bash/edit/write。
20. As a 前端用户, I want 浏览器能读到 `Retry-After`, so that 后续可以做倒计时提示。
21. As a 模型, I want 工具被限流时收到明确的错误文本, so that 我能向用户解释稍后重试。
22. As a 审计者, I want 限流只影响请求准入、不改变消息与执行审计语义, so that 现有账本仍然可信。

## Implementation Decisions

### 范围与身份

| 路径 | 维度 | 计数 |
|---|---|---|
| `/api/chat/**`、`/api/models/**`、`/api/kb/**`、`/api/auth/me`、`/api/auth/change-password`、`/api/auth/logout-all` | userId | RPM |
| `/api/exec`、`/api/kb/search`（TOOL） | `X-User-Id` | RPM |
| `POST /api/auth/login`、`POST /api/auth/register`、`POST /api/auth/refresh` | client IP | RPM |
| `POST /api/chat/sessions/{id}/messages` | userId | TPM（额外） |
| `/api/health`、`OPTIONS` | — | 不限 |

- RPM 过滤器放在 `JwtAuthenticationFilter` 与 `ToolTokenFilter` 之后；USER 从 `SecurityContext` 取 `AuthenticatedUser.userId()`，TOOL 从 `X-User-Id` 取。
- TOOL 路径只有已通过 `ToolTokenFilter` 获得 `TOOL` 权限时才读取 `X-User-Id`；无效服务 token 不消耗任何用户额度，交给 Security 返回 401。
- TOOL 路径缺少 `X-User-Id` 时跳过限流，交给现有 `ToolIdentity` 校验返回 400。
- IP 使用 `request.getRemoteAddr()`；M1 不信任 `X-Forwarded-For`，反向代理后的可信来源配置留到后续。
- 认证端点共用同一个 IP 额度 key，不能通过轮换 login/register/refresh 绕过。

### 算法与 Key

固定 60s 窗口；窗口起点为 `epochSecond / windowSeconds`，key 中带窗口序号：

```
rl:rpm:user:{userId}:{window}
rl:tpm:user:{userId}:{window}
rl:rpm:ip:{ip}:{window}
```

Lua（单 key）：

```lua
local count = redis.call('INCRBY', KEYS[1], ARGV[1])
if count == tonumber(ARGV[1]) then
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return {count, redis.call('PTTL', KEYS[1])}
```

- RPM 请求进入时 `INCRBY 1`；失败请求同样计数。
- TPM 预检查只读当前计数，不写；每条 `message_end` 用 `INCRBY tokens` 记账。
- TTL 为窗口长度 + 60s 余量，避免历史 key 堆积。
- `Retry-After` = `ceil(PTTL / 1000)`，最小 1 秒。
- 固定窗口边界允许瞬时 2 倍；M1 接受，后续需要平滑再换令牌桶。

### TPM 记账

- `PromptStream` 增加 `userId`；`openPrompt` 在创建 run 行与 pending 用户消息之前检查当前窗口 token 数。
- 每条 assistant `message_end` 取 `usage.totalTokens`；缺失时回退 `input + output`，累加到 Redis。
- 被 abort 的轮次按 Java 实际读到的 `message_end` 计数；断连后未读到的 usage 不计，作为已知误差。
- 不做每请求 DB 汇总；`runs` 仍是业务账本，Redis 只是限流账本。
- 单次运行可小幅超过 TPM，下一轮被拦截；不做输入 token 预估或预留。

### 429 与契约

- 新增 `RATE_LIMITED(429, retryable=true)`。
- 响应体沿用 `{error:{code,message,retryable}}`，message 区分 `request rate limit exceeded; retry in Ns` / `token rate limit exceeded; retry in Ns`。
- 响应头 `Retry-After: <秒>`；CORS `Access-Control-Expose-Headers: Retry-After`。
- TPM 拒绝发生在 SSE 头之前，不写 pending 消息、不创建 run 行。
- 契约 §7/§8 增加 429 与 `Retry-After` 说明，纯增量，不升主版本。

### 配置

| 配置 | 默认值 | 说明 |
|---|---|---|
| `app.rate-limit.enabled` | `true` | 关闭时 filter 与 TPM 检查直接放行 |
| `app.rate-limit.user-rpm` | `120` | 每用户每分钟请求数 |
| `app.rate-limit.user-tpm` | `100000` | 每用户每分钟 token 数 |
| `app.rate-limit.auth-ip-rpm` | `10` | 认证端点每 IP 每分钟请求数 |
| `app.rate-limit.window` | `60s` | 固定窗口长度 |

全部支持环境变量覆盖；`enabled=false` 是事故开关。

### Metrics

- 依赖：`spring-boot-starter-actuator` + `micrometer-registry-prometheus`（Boot BOM 管版本）。
- 独立管理端口 `MANAGEMENT_PORT`（默认 `9091`），只暴露 `prometheus`；显式开启 `management.prometheus.metrics.export.enabled=true`，避免 Boot 默认不注册 scrape endpoint。
- 指标（低基数，不带 userId/IP）：
  - `agentcore_rate_limit_rejections_total{scope="user-rpm|user-tpm|ip-rpm"}`
  - `agentcore_rate_limit_fail_open_total{scope=...}`
  - `agentcore_rate_limit_checks_total{scope,result="allowed|rejected"}`
  - `agentcore_rate_limit_check_seconds`（Timer）
- 日志：触发与 fail-open 打 WARN；按 scope 做简单节流，避免刷屏。

### 失败模式

- Redis 不可用：`RateLimitService` 捕获异常，记录 fail-open 指标与节流 WARN，放行请求；TPM 同理。
- Redis 恢复后计数从零开始，窗口内可能短暂放宽；可接受。
- 限流组件不参与认证/授权判断，关闭它不改变 401/403 语义。

### 测试隔离

- `AbstractIntegrationTest` 将额度调高（如 RPM/TPM 999999、IP 999999），保持限流逻辑开启，避免现有测试被 429。
- 限流专项测试用 `@TestPropertySource` 覆盖低额度与短窗口，只影响自己的 context。

## Testing Decisions

- 只测外部行为：HTTP 状态/头/envelope、Redis 计数、metrics 计数、是否创建投影行；不测 Lua 内部实现。
- 测试缝：
  1. `RateLimitService` + 真实 Redis（Testcontainers）：RPM 计数、TPM 累加、TTL/Retry-After、fail-open、固定窗口切换。
  2. `RateLimitFilter` + MockMvc：用户 RPM 429、TOOL 端点按 `X-User-Id`、无效服务 token 不消耗额度、认证 IP 429、健康/OPTIONS 不限制、`Retry-After` 与 CORS 暴露头。
  3. `ChatServiceImpl`：TPM 超限时 `openPrompt` 抛 `RATE_LIMITED` 且不写 run/pending 行；`message_end` 增量记账；`enabled=false` 放行。
  4. Gateway：断言 session 只注册 `kb_search`、`run_code`（AC2）。
  5. Metrics：`SimpleMeterRegistry` 断言 rejections/fail-open/checks 计数。
- 先例：`AbstractIntegrationTest`（Redis MySQL Testcontainers）、`MockMvc` 控制器测试、`FakeGateway`、gateway `pool.test.ts`。

## Out of Scope

- 滑动窗口、令牌桶、漏桶等更平滑的算法。
- per-model / per-provider / per-session 配额。
- 可信反向代理与 `X-Forwarded-For` 解析。
- 前端倒计时按钮与额度展示页。
- FR-7 的用量看板、trace 与计费。
- OAuth 登录与账号风控。
- 对 KB 内部重试、RabbitMQ 消费、Qdrant/embedding 出站调用做限流。
- 精确 token 计费；断连未读 usage 不补记。

## Further Notes

- 固定窗口在边界可能瞬时 2 倍，属于已知取舍；若线上出现尖峰再评估令牌桶。
- 多实例部署时 Redis 共享计数，语义保持一致；单实例与多实例无差异。
- 429 只影响准入，不改变消息投影、执行审计与工具结果语义。
- 管理端口默认 `9091`，部署时只在内网/本机抓取；若同端口暴露，需要额外的认证/网络策略。
- AC 映射：AC1 复用现有认证测试；AC2 增加 gateway 工具白名单断言；AC3 由 RPM/TPM 的 429 + `Retry-After` + 窗口恢复测试覆盖。
