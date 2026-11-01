# 限流换成令牌桶，并支持可信代理后的真实客户端 IP

Date: 2026-10-30

## Status

Accepted

## Context

FR-6 的限流目前是**固定窗口**：Redis 里按 `rl:<scope>:<id>:<windowIndex>` 自增，TTL 取窗口剩余时间。它有两个已知问题：

1. **窗口边界允许 2× 突刺**：`[0,60s)` 与 `[60s,120s)` 交界处，单用户在几毫秒内可以打满两倍配额；
2. `Retry-After` 只能是「等到窗口结束」，即使用户只需要再等 200ms 补一个令牌。

另一个独立问题是客户端 IP：`RateLimitFilter` 用 `request.getRemoteAddr()`。部署在反向代理后时所有请求的 IP 都是代理地址，`auth-ip-rpm=10` 会退化成**全站共享 10 次/分钟**，OAuth/登录路径直接被自己打死。

## Decision

1. **换成令牌桶**（Redis + Lua 原子实现）：
   - 桶容量 = 配额 `limit`，补充速率 = `limit / window`；
   - 请求消耗 1 个令牌（RPM、认证 IP），TPM 先 peek、结束按实际 token 数 debit；
   - TPM 的桶容量同样等于 `limit`（允许瞬时用满一分钟额度），debit 允许透支到 `-limit` 为止，避免一次超长回答直接封死后续所有请求；
   - `Retry-After` 由「还差多少令牌 / 补充速率」计算，精确到秒向上取整；
   - 失败仍 **fail-open** 并计数；旧 key 在一个窗口内自然过期，不需要数据迁移。
2. **可信代理白名单**：新增 `RATE_LIMIT_TRUSTED_PROXIES`（CIDR 列表，默认空）。
   - 仅当 `remoteAddr` 命中白名单时才解析 `X-Forwarded-For`；
   - 解析时从**右向左**取第一个非可信地址（左侧条目由客户端可伪造，必须忽略）；
   - 白名单为空时行为与今天完全一致；
   - 只支持 `X-Forwarded-For`，不解析 RFC 7239 `Forwarded`。

## Considered Options（被否的备选）

- **滑动窗口日志（ZSET）** — 最精确，但每请求一条 ZSET entry，内存随 QPS 线性增长，多实例下 Redis 压力大，`Retry-After` 也要扫窗口 → 否决。
- **保留固定窗口，只写文档** — 本切片的目的就是消掉 2× 突刺 → 否决。
- **无条件信任 `X-Forwarded-For`** — 客户端可伪造 IP 绕过限流 → 否决。
- **只做单层代理的最左/最右解析** — 多层代理与伪造场景下都会取错，必须按「最右侧非可信」解析 → 否决。
- **TPM 桶容量小于 limit** — 会让长回答被无谓卡住；capacity=limit 才是「一分钟额度」的正确语义 → 否决。

## Consequences

- 限流从「窗口内计数」变成「连续补充」，同一配额下的瞬时容忍度更平滑；`Retry-After` 变得更短更准。
- 旧固定窗口 key 不再被读取，会在一个窗口内自然消失，无需清理。
- 令牌桶状态用 `HSET` 存 `tokens` 与 `ts` 两个字段，单 key 内存恒定。
- 可信代理配置写错（例如白名单为空却部署在代理后）仍会退化成全站共享 IP 配额；README 必须写明。
- IPv6 客户端以完整地址作为桶 key；不做地址归并（与今天一致）。
