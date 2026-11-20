# 沙箱预热池验收报告（20 run_code 并发，pool off/on 对照）

日期：2026-10-25（执行于 2026-10-26）
对应 spec：`docs/specs/2026-10-25-sandbox-pool.md`
关联：`docs/requirements.md` FR-3、ADR-0026、ADR-0017（修订）
工具：`tools/loadtest`
原始数据：`docs/research/2026-10-25-sandbox-pool-artifacts/`
（每组 `summary.json`、`turns.jsonl`、`exec-records.tsv`、`sandbox-metrics.txt`）

## 环境快照

| 项 | 值 |
|---|---|
| 主机 | Apple M5 / 16 GB / macOS 26.6.2 |
| Docker | 本机 Docker daemon |
| Java / gateway | 本地进程（JDK 21 / Node 24.21.0），1 个 server + 5 个 gateway |
| MySQL / Redis / Qdrant / RabbitMQ / Silo | `tools/loadtest` 一次性栈（不读写既有本地库） |
| 沙箱镜像 | `agent-core-sandbox:python-3.12`（本机已有，运行时不构建/不拉取） |
| 模型 | 真实 DeepSeek `deepseek-chat`（`https://api.deepseek.com`） |
| 限流 | `RATE_LIMIT_USER_RPM=6000`、`USER_TPM=100000000`、`AUTH_IP_RPM=10000` |
| 沙箱并发 | `SANDBOX_GLOBAL_CONCURRENCY=12`、per-user 2、queue timeout 120s（两组相同） |
| 池参数 | group on：`SANDBOX_POOL_ENABLED=true`、target 12、max 16、idle TTL 10m、2 路补池 |

## 方法

- 每组都从全新一次性栈开始；harness 只打 Java 公开 HTTP API（与 web 同缝）。
- 负载：200 用户（180 chat + 20 run_code），单轮；5 个 gateway，每个用户会话由 Java 路由到 gateway。
- pool off：`SANDBOX_POOL_ENABLED=false`，每次执行冷创建容器（M1 行为）。
- pool on：先 `LOADTEST_WARMUP_MS=30000` 等池填满再开始；指标在运行结束时摘录。
- 工具回传延迟取 SSE `tool_call` → `tool_result` 间隔；队列/执行时长取 `exec_records.queue_ms` / `duration_ms`。
- 运行后校验 200 个会话的消息投影：每个 completed turn 恰好一条 user 消息、assistant 文本与流式事件一致。
- 两组均无 gateway/server 重启，差异只来自预热池开关（以及运行时序噪声）。

## 结果

### 总览

| 指标 | pool off | pool on |
|---|---|---|
| 用户 / 轮次 | 200 / 1 | 200 / 1 |
| 最终状态 | 200/200 completed | 200/200 completed |
| 硬错误 / 限流 / 上游 429 / 断流 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| 重试 | 0 | 0 |
| 总时长 | 28.1s | 25.3s |
| 消息校验 | 200 sessions / 200 turns，全部 0 差异 | 同左 |
| `run_code` 成功执行 | 20/20 | 20/20 |

### `run_code` 工具回传

| 指标 | 目标 | pool off | pool on |
|---|---|---|---|
| 样本 | — | 20 | 20 |
| P50 | ≤ 10s | 9.80s | **9.08s** |
| P95 | ≤ 25s | 13.79s | **13.12s** |
| P99 | — | 14.17s | 13.66s |
| max | — | 14.17s | 13.66s |

两组均达标；pool on 的 P50 比 off 低约 0.7s。本机 16GB、全局并发 12 下，
端到端工具回传主要由模型工具回合、排队与代码执行本身占据，容器冷启动只是其中一段，
因此池化带来的绝对值改善小于“去掉整个冷启动”的预期。

### 相位拆分

| 指标 | pool off | pool on |
|---|---|---|
| 队列等待 avg / max | 2.15s / 6.45s | 1.62s / 5.03s |
| 代码执行 avg / max（审计 `duration_ms`） | 2.44s / 4.11s | 3.58s / 4.21s |
| 容器生命周期 avg / max | 2.27s / 3.79s（20 次冷创建） | 1.32s / 2.59s（12 次填池 + 20 次补池） |
| 取池命中 / 冷创建兜底 | 0 / 20 | **20 / 0** |

### 池状态与泄漏

- pool on 结束时指标：`sandbox_pool_idle=12`、`sandbox_pool_creating=0`，
  `sandbox_pool_checkout_total{result="hit"}=20`，无 `result="miss"`；
  `sandbox_pool_cold_fallback_total` 未出现（0 次）。
- 两组结束后 `docker ps -a --filter label=agent-core.sandbox` 均为 0：
  执行容器全部删除；pool on 关闭时 idle 池容器也被清理。
- `exec_records` 20 行全部 `succeeded`；池创建/补池没有写审计行。

## 对照验收线

| 验收项 | 结果 | 判定 |
|---|---|---|
| 20 并发 run_code，pool on 工具回传 P50 ≤ 10s | 9.08s | ✅ |
| 20 并发 run_code，pool on 工具回传 P95 ≤ 25s | 13.12s | ✅ |
| 运行后执行容器遗留数为 0 | 0 | ✅ |
| 池 idle 回到 target | 12 | ✅ |
| 无硬错误，聊天与其他工具不回归 | 200/200 completed；chat 0 错误 | ✅ |
| pool off 基线可复查 | 见 artifacts | ✅ |

## 观察与后续

1. 本轮 pool on 的 `run_code` P50/P95 都达标，但相对 pool off 的收益有限；
   冷启动约 2.3s，仅占工具回传的一部分。若后续要继续压低 P50，应优先看
   工具回合固定开销与代码执行时长，而不是再加大池 target。
2. 池填充/补池本身稳定：12 个 idle 在预热窗口内完成，20 次执行全部命中，
   无补池失败、无冷创建兜底、无遗留容器。
3. 本机 :3001 被其他 workspace 的 web 进程占用，本轮 gateway 使用
   `LOADTEST_GATEWAY_BASE_PORT=3101`；harness 已支持该变量。
4. `sandbox_container_lifecycle_ms` 同时统计填池与冷创建，因此 pool on 的
   count 是 32（12+20），不能直接与 pool off 的 20 做样本数对比；avg/max 仍可比较。

## 收尾提醒

- 一次性栈已通过 `npm run stack:down` 清理；未触碰任何既有本地数据库。
- DeepSeek key 仅经环境变量注入，未写入仓库或报告。
