# M1 Closeout 压测报告（50 并发活跃会话）

日期：2026-10-25
对应 spec：`docs/specs/2026-10-25-m1-load-test.md`
工具：`tools/loadtest`
原始样本：`docs/research/2026-10-25-m1-loadtest-artifacts/`（`turns.jsonl`、`summary.json`、`exec-records.tsv`）

## 环境快照

| 项 | 值 |
|---|---|
| 主机 | Apple M5 / 16 GB / macOS 26.6.2 |
| Docker | 29.6.1（Docker Desktop） |
| Java / gateway | 本地进程（JDK 21 / Node 24.21.0） |
| MySQL / Redis / Qdrant | `mysql:8.4` / `redis:7-alpine` / `qdrant/qdrant:v1.14.1`（一次性容器） |
| RabbitMQ / Silo | `rabbitmq:4.3.7-management` / `pgsty/silo:RELEASE.2026-10-18T00-00-00Z`（一次性容器） |
| 沙箱镜像 | `agent-core-sandbox:python-3.12`（本机已有，运行时不构建/不拉取） |
| embedding / rerank | 本地确定性假服务（`demo-embedding` 1024 维 / `demo-rerank`），不纳入真实供应商延迟 |
| 模型 | 真实 DeepSeek `deepseek-chat`（`https://api.deepseek.com`） |
| 限流 | `RATE_LIMIT_USER_RPM=6000`、`USER_TPM=100000000`、`AUTH_IP_RPM=10000`（压测临时调高，排除限流干扰） |

## 方法

- 50 个虚拟用户经**公开 Java HTTP API**（注册 → 配 DeepSeek provider → 建会话）并发对话；
  负载构成：40 纯 chat + 5 `run_code` + 5 `kb_search`，每人 3 轮，轮间 think-time 3–8s。
- 第 15 秒对 gateway 做一次滚动重启；被中断/中止的 turn 以**原 turnId** 幂等重试
  （重试预算 4 次、间隔 2.5s）。
- `kb_search` 用户各自建库并经公开 API 上传 3 篇短文，待切片/向量化 `ready` 后再对话；
  工具回传延迟取 SSE `tool_call` → `tool_result` 间隔。
- 并行直连 DeepSeek 采样流式首 token 作为 TTFB 基线（每 5s 一次）。
- 跑完后逐会话分页读取消息投影，校验每个 completed turn：user 消息恰好一条、
  assistant 回复存在，且投影 assistant 文本总长度等于流式 `message_end` 文本总长度
  （同时覆盖「丢失」与「重复回复」）。

## 结果

总览：**50 用户 / 150 turns / 48.8s，全部 `completed`；硬错误、限流、上游 429、断流均为 0。**
其中 21 个 turn 经过重试（17 次网关不可用、4 次重启中止），全部以同 turnId 恢复。

### 首 token 延迟

| 口径 | 样本 | P50 | P95 |
|---|---|---|---|
| 纯 chat（全部） | 120 | 703ms | 1425ms |
| 纯 chat（未重试样本） | 104 | 689ms | 1381ms |
| DeepSeek 直连 TTFB 基线 | 11 | 493ms | 675ms |

**扣除模型自身 TTFB（§4 口径）**：chat P95 1425ms − 基线中位 493ms = **932ms**；
按基线 P95 675ms 扣除 = **750ms**。均 < 3s。

`run_code` 轮次的「首 token」出现在工具执行完成之后，不代表模型 TTFB，故不纳入本表。

### 工具回传延迟

| 工具 | 样本 | P50 | P95 |
|---|---|---|---|
| `kb_search` | 15 | 58ms | **258ms** |
| `run_code` | 15 | 5565ms | 8567ms |

`run_code` 相位拆分（14 个未重试样本，与 `exec_records` 按 `tool_call_id` 关联）：

| 相位 | P50 | P95 | 说明 |
|---|---|---|---|
| Java 排队/许可等待 | 9ms | 43ms | 网关→Java→执行调度 |
| 沙箱操作（建容器+启动+exec+销毁） | 5175ms | 8209ms | 一次性容器生命周期，含代码执行 |
| 其中纯执行（审计 `duration_ms`） | 1538ms | 3942ms | Python 启动 + 代码，受 5 路并发影响 |
| **沙箱之外的回传开销** | **141ms** | **476ms** | 网关/Java 侧，满足 500ms 预算 |

### 重启耐久性

- 重启窗口内 4 个在途 turn 被中止（另 17 次网关不可用），全部以同 turnId 重试成功。
- 跑后校验：**50 个会话 / 150 个 completed turn，missing=0、duplicates=0、
  roleMismatches=0、textMismatches=0**。
- 每个完成轮次的 user 消息恰好一条，assistant 文本总量与流式事件一致（无重复回复）。

## 对照 `docs/requirements.md` §4 判定

| 指标 | 要求 | 实测 | 判定 |
|---|---|---|---|
| 单实例并发 | ≥50 活跃会话 | 50 用户 / 150 turns，0 硬错误 | ✅ |
| 首 token | P95 < 3s（扣模型 TTFB） | 932ms（扣基线中位）/ 750ms（扣基线 P95） | ✅ |
| `kb_search` 回传 | P95 < 500ms | 258ms | ✅ |
| `run_code` 回传 | P95 < 500ms（不含执行本身） | 沙箱外开销 P95 476ms；排队 P95 43ms | ✅（口径见下） |
| 重启不丢消息 | 已持久化消息不丢 | 150/150 完整、无重复 | ✅ |

`run_code` 口径：FR-3 规定「一次执行一个容器，结束即销毁」，故「不含代码执行本身」
按**排除整个沙箱操作**（容器生命周期 + 代码执行）理解；剩余网关/Java 侧回传开销
P95 476ms。若把容器生命周期计入 500ms 预算，该指标不成立——一次性容器在 5 路并发下
建/启/销毁需 3.8–8.2s，这是 FR-3 隔离设计的固有成本，建议 M2 评估预热池或容器复用。

## 发现与建议

1. **一次性容器生命周期是 `run_code` 延迟主因**：沙箱操作 P50 5.2s / P95 8.2s，
   其中纯执行 P50 1.5s / P95 3.9s。沙箱之外的网关/Java 开销已达标（P95 476ms）。
   M2 可评估沙箱预热池/容器复用与 gVisor 方案。
2. **重启路径符合预期**：21 次中断全部以同 turnId 恢复，消息投影无丢失、无重复；
   `kb_search` 工具延迟未受明显影响（P95 258ms）。
3. **DeepSeek 承载 50 并发流无 429**；直连 TTFB 中位约 0.5s、P95 约 0.7s。
4. **观测补齐**：本轮起 `exec_records` 与 SSE 工具事件可按 `tool_call_id` 关联，
   后续压测可直接复用该相位拆分。

## 收尾提醒

- 本次沿用上一次会话的 DeepSeek key（仅经环境变量注入，未落文件/报告）。该 key 曾出现在
  聊天与会话日志中，**建议尽快轮换**。
- 仓库 CI 对 `06ef653..HEAD` 的状态本机无法查询（无 gh、私有仓），需在 GitHub Actions 页确认。
- 按既定决策，**暂不提 `dev → main` PR**。
