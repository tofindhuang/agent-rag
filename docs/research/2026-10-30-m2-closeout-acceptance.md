# M2 收尾验收记录：限流调优与滚动重启冒烟

日期：2026-10-30
对应 spec：`docs/specs/2026-10-30-m2-hardening-closeout.md`
关联：ADR-0030、ADR-0031
工单：`docs/tickets/m2-hardening-closeout/01–03`

## 1. 限流令牌桶

### 自动化

| 套件 | 覆盖 | 结果 |
|---|---|---|
| `RateLimitServiceIntegrationTest` | 配额边界（第 limit+1 次拒绝）、`Retry-After`、窗口后恢复、**连续补充**（2s 窗口睡 1.1s 即恢复 1 个令牌）、TPM 透支上限为一倍容量 | 7 例通过（新增 2 例） |
| `ClientIpResolverTest` | 白名单为空、直连不可信、可信代理后取真实 IP、**左侧伪造条目被忽略**、多层代理、非 IP 条目、CIDR 前缀、IPv6、非法配置启动失败 | 9 例通过 |
| `UserRpmRateLimitIntegrationTest` / `AuthIpRateLimitIntegrationTest` / `TpmRateLimitIntegrationTest` / `RateLimitMetricsIntegrationTest` | 既有 429 语义、TPM 第二轮拒绝与窗口后恢复、指标暴露 | 全部通过 |

### 行为差异（相对固定窗口）

- 同一配额下不再出现「窗口交界处 2× 突刺」；请求按 `limit / window` 的速率连续补充。
- `Retry-After` = 补够 1 个令牌所需时间（向上取整到秒），不再是「等到窗口结束」。
- TPM 先 peek（`tokens > 0`）、调用结束后按实际 token 数 debit；透支下限为 `-limit`，一次超长回答不会把用户永久锁死。
- 旧固定窗口 key（`rl:<scope>:<id>:<windowIndex>`）不再读取，在一个窗口内自然过期，无需清理。

## 2. 可信代理

- 默认 `RATE_LIMIT_TRUSTED_PROXIES` 为空 → 仍用 `remoteAddr`，升级零行为变化。
- 命中白名单时才解析 `X-Forwarded-For`，并且从右向左取**第一个非可信地址**；客户端在最左侧伪造的条目不会被采信。
- 非法 CIDR 在启动时直接失败（配置错误不拖到运行期）。
- 未做 RFC 7239 `Forwarded` 解析（见 ADR-0030）。

## 3. gVisor 评估

- 结论：M2 暂不启用；产出 ADR-0031 与 `docs/research/2026-10-30-gvisor-evaluation.md`。
- **未实测**：本机 Docker 只有 `runc`，未安装 `runsc`；报告已显式标注，未把推测写成性能数据。
- 迁移清单 6 步（runtime 配置、节点安装、镜像复测、配额重估、压测对照、不兼容清单）已落档。

## 4. 滚动重启轻量冒烟

一次性栈（独立端口 + 本地 OpenAI 兼容桩，**不触碰本机共享库**），2 个 gateway 实例共享 `GATEWAY_SESSION_DIR`：

| 步骤 | 结果 |
|---|---|
| 建 6 个会话并各跑一轮 | 6/6 `run_settled`；实例分布 4 / 2 |
| 在飞轮次（桩延迟 4s）中途对实例 0 `POST /admin/drain` | 该轮**完整结束**（4.18s，收到 `run_settled`），SSE 未被切断 |
| drain 后实例 0 退出 | 会话路由自动改到实例 1；同会话再发一轮正常结束 |
| 重启实例 0 | `/health` 恢复、`draining=false`；再发一轮正常结束 |

说明：这是覆盖本轮改动面（SSE / 工具链 / 压缩事件）的轻量验证；200 用户 × 5 实例的完整滚动压测结论复用 `docs/research/2026-10-25-m2-multi-instance-loadtest-report.md`，未重跑。

## 5. 未覆盖

- 未重跑 200×5 全量压测与沙箱池压测（复用既有报告，见 closeout spec 的证据索引）；
- gVisor 未实测；
- 可信代理只做了单元/集成测试，未在真实反代（nginx/envoy）后跑端到端。

## 6. 残余风险

- 令牌桶的「瞬时用满容量」语义与固定窗口不同，运营侧解读配额时需注意；
- 代理白名单配错会退化为全站共享 IP 配额（文档已写）；
- 压测证据对应的 commit 早于本轮改动，轻量冒烟只覆盖改动面。
