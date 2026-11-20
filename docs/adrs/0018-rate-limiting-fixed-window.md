# 限流用固定窗口、Redis 故障 fail-open、TPM 用后记账

Date: 2026-10-25

## Status

Accepted

## Context

FR-6 要求 per-user RPM/TPM 限流、429 与重试窗口，并覆盖工具回调与认证端点。现有业务层没有限流；模型 token 消耗只有 `runs` 与 `messages` 的事后账本，没有请求前的额度控制。

限流是保护措施，不是身份边界：Redis 抖动时如果 fail-closed，会把聊天、KB 与登录一起打挂；但 fail-open 意味着短窗口内可能放过超额请求。算法也有取舍：固定窗口简单、TTL 即重试窗口，但边界可能瞬时 2 倍；令牌桶更平滑，但状态和解释成本更高。

## Decision

1. **固定 60s 窗口计数**：RPM 请求进入时 `INCRBY 1`，TPM 在每条 assistant `message_end` 时 `INCRBY totalTokens`；单 key Lua 原子完成 `INCRBY + 首次 PEXPIRE`，`Retry-After` 取 PTTL。
2. **RPM 用 Servlet Filter**，放在 JWT / ToolToken 之后；USER 取 `AuthenticatedUser`，TOOL 取 `X-User-Id`，认证端点按 `remoteAddr` 取 IP。TPM 预检查放在 `openPrompt` 创建 run 行之前。
3. **TPM 后付费**：提交时只检查当前窗口已用量，不预估/预留本轮 token；单次可小幅超限，下一轮拦截。记账口径优先 `usage.totalTokens`，缺失回退 input+output。
4. **Redis 故障 fail-open**：捕获异常、记 fail-open 指标与节流 WARN，放行请求；限流不参与 401/403。
5. **Metrics 不带 userId/IP 标签**：只按 `scope` 与 `allowed|rejected` 聚合，避免基数爆炸；独立管理端口暴露 Prometheus。
6. **全局开关** `app.rate-limit.enabled`，关闭时 filter 与 TPM 检查直接放行。

## Considered Options(被否的备选)

- **令牌桶 / 滑动窗口日志** — 边界更平滑，但每个用户需要更多 Redis 状态与解释成本；M1 先固定窗口，出现尖峰再换。
- **Redis 故障 fail-closed** — 额度更严格，但 Redis 成为聊天/KB 的单点；限流是保护而非身份判断 → 否决。
- **每请求从 `runs` 汇总 TPM** — 账本准确、跨重启，但 prompt 热路径要 join 并扫描窗口；Redis 计数更合适 → 否决。
- **提交前预估输入 token 并预留额度** — 可避免单次超限，但估算误差与预留回滚复杂；先允许小幅超限 → 否决。
- **metrics 带 userId / IP 标签** — 排障直观，但基数不可控 → 否决。

## Consequences

- 固定窗口边界允许瞬时 2 倍流量；这是已知取舍。
- 断连后 gateway 继续产生但 Java 未读到的 usage 不计入 TPM；重试产生的真实 usage 会累加。限流账本不追求与计费完全一致。
- Redis 恢复后计数从零开始，窗口内可能短暂放宽。
- 管理端口 `9091` 需要部署侧只在内网/本机抓取；同端口暴露需额外网络策略。
- 限流不改变消息投影、执行审计与工具结果语义；关闭限流不影响认证与工具白名单。
