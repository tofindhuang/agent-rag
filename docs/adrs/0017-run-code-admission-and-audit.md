# run_code 准入先于 SSE、审计 fail-closed、permit 可过期

Date: 2026-10-24

## Status

Accepted

## Context

契约 v1 §8 把 `run_code` 定为 SSE 流：`started` → `phase` / `stdout` / `stderr` → 唯一 `result`。契约没有定义「流开始后的错误帧」，错误只能以流开始前的 JSON envelope 出现。

FR-3 同时要求 per-user 并发上限、超限排队（AC4）、每次执行完整审计（AC3），以及断连即杀容器。排队意味着请求可能要等几十秒才能开始执行；如果把排队放进 SSE 流内，就产生三个问题：排队超时无法用契约内的事件表达；客户端/模型无法区分「正在排队」与「执行卡住」；`result` 唯一性被挑战。

此外，审计是安全与排障要求，但数据库可能短暂不可用；permit 存在 Redis 中，JVM 崩溃可能泄漏名额。

## Decision

1. **准入先于 SSE**：`/api/exec` 在写响应头之前完成 per-user 与全局 permit 获取，等待上限 120s；超时返回 `503 SANDBOX_QUEUE_TIMEOUT`。流内不出现 `queued` phase。
2. **审计 fail-closed**：准入通过后先插入 `exec_records`（`status=running`）再创建容器；插入失败则拒绝执行并释放 permit。终态以一条 UPDATE 落库；JVM 崩溃遗留的 `running` 行在启动时标记为 `aborted`。
3. **permit 可过期**：使用 Redisson `RPermitExpirableSemaphore`，lease 240s（覆盖排队 120s + 执行 60s + 余量）。加锁顺序固定为 per-user → global，避免排队请求占用全局名额造成跨用户饥饿。Redis 不可用时快速失败，不做进程内降级。
4. **输出截断不杀容器**：stdout+stderr 合计 64 KiB 后停止转发但继续 drain，保留退出码与终态，`result.truncated=true`。

## Considered Options(被否的备选)

- **SSE 先开流，用 `phase: queued` 表达排队** — 契约没有流内错误帧，排队超时只能断流或伪造 `result`；消费者需要新增合成语义 → 否决。
- **超过并发上限直接返回 429** — 违反 AC4「排队而非报错」→ 否决。
- **进程内信号量 / 非过期 Redis 信号量** — 单实例看似够用，但 JVM 崩溃会永久泄漏名额；进程内实现也无法支撑 M2 多实例 → 否决。
- **审计异步 best-effort** — 恰恰在失败时丢审计，违背 AC3 的目的；且无法解释「执行过但没有记录」→ 否决。
- **输出达到上限即杀容器** — 丢失退出码与合法尾部输出，`result` 语义失真 → 否决。

## Consequences

- 排队请求占用 Tomcat 线程最多 120s；M1 单实例、默认 8 全局并发下可接受，扩容时需改为异步准入（届时错误语义也要重新设计）。
- 契约增加三个沙箱错误码（`SANDBOX_UNAVAILABLE` / `SANDBOX_QUEUE_TIMEOUT` / `SANDBOX_CODE_TOO_LARGE`），属纯增量，不升主版本。
- Redis 故障只影响 `run_code`，聊天与知识库不受影响；代价是执行完全不可用而非降级放行。
- 审计表随会话彻底删除级联删除，保留期 90 天；与 NFR「沙箱执行产物保留 7 天」分开解释（后者指执行产物/文件）。
- 若未来引入多实例，permit 语义保持有效，但「排队请求占 Tomcat 线程」与启动巡检的重复执行需要重新评估。
