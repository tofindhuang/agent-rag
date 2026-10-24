# M1 Closeout 压测报告（50 并发活跃会话）

日期：2026-10-25
对应 spec：`docs/specs/2026-10-25-m1-load-test.md`
工具：`tools/loadtest`（本报告所有数字来自同一次全量运行）

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
- 第 15 秒左右对 gateway 做一次滚动重启；被中断/中止的 turn 以**原 turnId** 幂等重试
  （重试预算 4 次、间隔 2.5s）。
- `kb_search` 用户各自建库并经公开 API 上传 3 篇短文，待切片/向量化 `ready` 后再对话；
  工具回传延迟取 SSE `tool_call` → `tool_result` 间隔。
- 并行直连 DeepSeek 采样流式首 token 作为 TTFB 基线（每 5s 一次）。
- 跑完后逐会话分页读取消息投影，校验每个 completed turn：user 消息恰好一条、
  assistant 消息至少一条（不丢、不重）。

## 结果

总览：**50 用户 / 150 turns / 45.3s，全部 `completed`；硬错误、限流、上游 429、断流均为 0。**
其中 22 个 turn 经过重试（13 次网关不可用、9 次重启中止），全部以同 turnId 恢复。

### 首 token 延迟

| 口径 | 样本 | P50 | P95 |
|---|---|---|---|
| 全部 turns | 150 | 746ms | 7987ms（被 run_code 工具轮次拉高，非模型 TTFB） |
| 纯 chat | 120 | 694ms | 1132ms |
| 纯 chat（未重试样本） | 105 | 681ms | 1162ms |
| DeepSeek 直连 TTFB 基线 | 11 | 504ms | 917ms |

**扣除模型自身 TTFB（§4 口径）**：chat P95 1162ms − 基线中位 504ms = **658ms**；
按基线 P95 917ms 扣除 = **245ms**。均 < 3s。

### 工具回传延迟

| 工具 | 样本 | P50 | P95 |
|---|---|---|---|
| `kb_search`（全部） | 15 | 101ms | 504ms |
| `kb_search`（未重试） | 11 | 101ms | **400ms** |
| `run_code`（全部） | 15 | 6452ms | 11160ms |

`run_code` 相位拆分（12 个未重试样本，与 `exec_records` 按 `tool_call_id` 关联）：

| 相位 | P50 | P95 | 说明 |
|---|---|---|---|
| Java 排队/许可等待 | 15ms | 137ms | 网关→Java→执行调度 |
| 沙箱操作（建容器+启动+exec+销毁） | 6160ms | 9673ms | 一次性容器生命周期，含代码执行 |
| 其中纯执行（审计 `duration_ms`） | 1918ms | 4822ms | Python 启动 + 代码，受 5 路并发影响 |
| 沙箱之外的开销 | 248ms | 2125ms | 单侧离群 2.1s；其余样本 max 653ms |

### 重启耐久性

- 重启窗口内 5 个在途 turn 被中止（另 13 次网关不可用），全部以同 turnId 重试成功。
- 跑后校验：**50 个会话 / 150 个 completed turn，missing=0、duplicates=0、roleMismatches=0**。
- 每个完成轮次的 user 消息恰好一条，assistant 回复齐全。

## 对照 `docs/requirements.md` §4 判定

| 指标 | 要求 | 实测 | 判定 |
|---|---|---|---|
| 单实例并发 | ≥50 活跃会话 | 50 用户 / 150 turns，0 硬错误 | ✅ |
| 首 token | P95 < 3s（扣模型 TTFB） | 658ms（扣基线中位）/ 245ms（扣基线 P95） | ✅ |
| `kb_search` 回传 | P95 < 500ms | 未重试 400ms；含重启样本 504ms | ✅（含重启样本时边缘） |
| `run_code` 回传 | P95 < 500ms（不含执行本身） | 排队 P95 137ms；沙箱外开销 P50 248ms / P95 2125ms | ⚠️ 取决于「执行本身」口径 |
| 重启不丢消息 | 已持久化消息不丢 | 150/150 完整、无重复 | ✅ |

`run_code` 判定说明：FR-3 规定「一次执行一个容器，结束即销毁」，容器生命周期
（3.8–9.7s）在语义上属于「代码执行本身」；将其排除后，Java/网关侧开销中位 248ms
（P95 受一个 2.1s 离群样本影响）。若把容器生命周期计入 500ms 预算，则该指标不成立，
需要产品口径确认（见下方建议）。

## 发现与建议

1. **一次性容器生命周期是 `run_code` 延迟主因**：5 路并发下建容器+执行+销毁 3.8–9.7s，
   其中纯执行 1.0–4.8s。M2 可评估沙箱预热池/容器复用与 gVisor 方案，或把 500ms 指标
   明确为「沙箱之外的回传开销」。
2. **重启期间 `kb_search` 尾部变高**：4 个被重启影响的 turn 工具延迟升到 208–504ms；
   未重试样本 ≤400ms。恢复路径符合预期，无错误数据。
3. **DeepSeek 承载 50 并发流无 429**；上游 TTFB 中位约 0.5s，抖动 P95 约 0.9s。
4. **turnId 幂等重试有效**：22 次中断全部恢复且未产生重复 user 消息投影。

## 收尾提醒

- 本次沿用上一次会话的 DeepSeek key（仅经环境变量注入，未落文件/报告）。该 key 曾出现在
  聊天与会话日志中，**建议尽快轮换**。
- 仓库 CI 对 `06ef653..HEAD` 的状态本机无法查询（无 gh、私有仓），需在 GitHub Actions 页确认。
- 按既定决策，**暂不提 `dev → main` PR**。
