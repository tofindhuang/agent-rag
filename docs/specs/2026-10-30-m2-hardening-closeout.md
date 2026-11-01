# M2 硬化收尾：限流调优、gVisor 评估与 closeout

日期：2026-10-30
状态：Phase 1 已完成（Q1–Q4 逐问确认），待实现
关联 ADR：[0030](../adrs/0030-token-bucket-rate-limit-and-trusted-proxies.md)、[0031](../adrs/0031-gvisor-evaluation.md)、[0018](../adrs/0018-rate-limiting-fixed-window.md)、[0024](../adrs/0024-multi-instance-affinity-and-drain.md)、[0026](../adrs/0026-sandbox-warm-pool.md)
关联需求：FR-3、FR-6（限流）、§4 非功能（滚动更新）、§7 M2
涉及模块：server、docs（README/.env.example）

## Problem Statement

M2 只剩三件收尾工作：

1. 限流是固定窗口，窗口边界允许 2× 突刺，`Retry-After` 只能给到「窗口结束」；且客户端 IP 取 `remoteAddr`，部署在反向代理后时认证 IP 配额会退化成全站共享。
2. gVisor 评估尚未交付结论；requirements 的 R2 要求给出「要不要换 runtime」的判断。
3. M2 各切片的证据散落在多份 research 文档里，缺少一份收尾说明：哪些已验证、哪些仍有边界、下一阶段接什么。

## Solution

- 限流改令牌桶（Redis + Lua），`Retry-After` 按补充速率计算；新增 `RATE_LIMIT_TRUSTED_PROXIES`，只在可信代理后解析 `X-Forwarded-For` 的最右侧非可信地址。
- gVisor：产出评估报告 + ADR，结论为「M2 暂不启用」，附 6 步迁移清单；明确标注未实测。
- closeout：复用既有压测证据 + 一次轻量滚动重启冒烟（2 实例 × 20 会话），产出 closeout spec 与验收记录，并同步 requirements / README 的状态与承诺边界。

## Implementation Decisions

### 1. 令牌桶

- `RateLimitProperties` 增加 `trustedProxies: List<String>`（CIDR，默认空）。
- Lua 单脚本实现 peek / consume / debit 三种模式，状态存 `HSET key tokens ts`，TTL = 窗口长度。
- 配额语义：容量 = `limit`，补充速率 = `limit / window`；RPM 与认证 IP 每次消耗 1；TPM 先 peek（`tokens > 0`），结束后按实际 token 数 debit，允许透支到 `-limit`。
- `Retry-After = ceil((需要 - 现有) / 速率)`，不小于 1 秒。
- 失败 fail-open + 既有指标不变；旧固定窗口 key 自然过期。

### 2. 可信代理

- `ClientIpResolver`：白名单为空 → 直接返回 `remoteAddr`；`remoteAddr` 不在白名单 → 同样返回 `remoteAddr`；否则解析 `X-Forwarded-For`，从右向左取第一个非可信地址，全部可信则取最左。
- CIDR 支持 IPv4/IPv6（按位比较，要求同族），配置非法时启动失败。
- `RateLimitFilter` 的两个 IP 检查改走该解析器。

### 3. gVisor

- 只交付 `docs/adrs/0031-gvisor-evaluation.md` 与 `docs/research/2026-10-30-gvisor-evaluation.md`，不落代码开关。
- 报告标注「基于官方文档与现有隔离参数分析，未实测」。

### 4. Closeout

- 轻量冒烟：2 个 gateway 实例 + 20 会话顺序重启，确认 0 断流（覆盖切片 3/4 对 SSE 与工具链的改动）。
- 复用：200×5 滚动重启报告、60×1 容量、沙箱池压测报告（均标注日期）。
- 承诺边界写清：滚动更新不断流；**崩溃/强杀仍是 partial**；单机多实例，不含跨主机。
- 状态同步：requirements §7 M2 行、m2-multi-instance spec 的「M2 仍不算完成」、README 的限流配置与边界说明。

## Out of Scope

- 滑动窗口日志算法；
- RFC 7239 `Forwarded` 解析；
- gVisor 代码开关与实测；
- 跨主机会话迁移、Java 多实例；
- 全量 200×5 压测复跑（复用既有报告）。

## Acceptance

1. 令牌桶：窗口边界不再允许 2× 突刺（等间隔请求在第 `limit+1` 次被拒，且 `Retry-After` 反映补充时间）；TPM 的 peek/debit 语义与既有测试一致。
2. 可信代理：白名单为空时 IP 与今天一致；配置代理后按真实客户端分桶；伪造的左侧 XFF 不影响判定；非法 CIDR 启动失败。
3. gVisor：ADR + 报告存在，结论与迁移清单完整，且明确标注未实测。
4. closeout：轻量滚动重启 20/20 无断流；closeout 文档列齐证据索引、未覆盖项与承诺边界；requirements/README/spec 状态同步。
5. server 全量测试、web typecheck/build、gateway 测试全绿。

## Residual Risks

- 令牌桶把「窗口内硬上限」换成「连续补充」，同一分钟内仍然可以突发用满容量；这是刻意的取舍。
- 可信代理配置错误（该配没配）仍会让认证 IP 配额全站共享；只能靠文档与部署检查。
- gVisor 结论未实测，将来启用前必须重跑沙箱压测与兼容性清单。
- closeout 复用旧压测报告：报告对应的是当时的 commit，本轮只做轻量冒烟覆盖改动面。
