# run_code 预热容器池与并发调整

日期：2026-10-25
状态：已实现（验收见 `docs/research/2026-10-25-sandbox-pool-loadtest-report.md`）
关联：`docs/requirements.md` FR-3；ADR-0017（修订）、ADR-0026；`docs/specs/2026-10-24-sandbox-executor.md`；`CONTEXT.md`

## Problem Statement

M1 的 `run_code` 每次执行都冷启动一个一次性容器。实测容器生命周期 P50 5.2s / P95 8.2s，纯执行 P50 1.5s / P95 3.9s；20 个 run_code 用户、全局并发 8 时，工具回传 P50 约 59s。

用户体感是「模型调用了代码工具，然后等了一分钟」。主要成本有两段：全局并发 8 造成的排队，以及每次建/启容器的固定开销。FR-3 要求一次执行一个容器、结束即销毁，因此不能简单改成容器复用。

## Solution

- 保持「一次执行一个容器」：提前创建、启动 idle 池容器，每次执行原子取一个，执行后立即销毁，不回流、不复用。
- 池只是加速层：池空/坏容器/补池失败都退回冷创建；冷创建失败才报 `SANDBOX_UNAVAILABLE`。
- 全局并发从 8 提到 12，per-user 仍为 2；准入与排队语义不变。
- 生产默认开启，`SANDBOX_POOL_ENABLED=false` 可回退到 M1 行为；测试环境关闭。
- 目标是 20 个并发 run_code、16GB 参考机下，工具回传 **P50 ≤ 10s、P95 ≤ 25s**。

## User Stories

1. 作为用户，我要在并发 run_code 时不必等容器冷启动，以便工具结果更快回来。
2. 作为用户，我要预热池不改变「每次执行在独立容器里运行」，以便不同执行之间没有文件和进程残留。
3. 作为用户，我要池空了也能执行，而不是收到「池耗尽」错误。
4. 作为用户，我要池管理器故障不影响聊天或其他工具。
5. 作为用户，我要长代码执行仍按 30s 默认 / 60s 上限超时，预热不改变超时语义。
6. 作为用户，我要断连时容器仍被立刻杀死，不因为来自池而泄漏。
7. 作为用户，我要审计仍能查到每次执行的完整代码、资源消耗与终态。
8. 作为运维者，我要看到池 idle/creating、取池等待、冷创建兜底与补池失败指标。
9. 作为运维者，我要能一键关闭预热池，回到 M1 的一次性冷创建行为。
10. 作为运维者，我要 JVM 崩溃后遗留的池容器在下次启动被清理。
11. 作为运维者，我要池容器不会被孤儿巡检按一次性容器年龄误杀。
12. 作为运维者，我要镜像更新后池容器不会继续使用旧镜像。
13. 作为安全负责人，我要池容器与执行容器使用完全相同的隔离参数。
14. 作为安全负责人，我要池容器在执行前没有用户代码或用户数据。
15. 作为安全负责人，我要执行容器结束即删除，不能回到池中复用。
16. 作为开发者，我要池管理器只依赖一个容器工厂端口，以便用 fake 覆盖边界条件。
17. 作为开发者，我要取池、补池、TTL、镜像变化都有确定性测试，不依赖真实 Docker。
18. 作为开发者，我要 Docker 集成测试证明隔离参数与「执行后删除」。
19. 作为开发者，我要池 on/off 的对照压测，而不是只看单次延迟。
20. 作为维护者，我要 20 并发 run_code 的 P50/P95 与容器残留数进入报告，以便判断目标是否达成。

## Implementation Decisions

### 1. 不变量

- 每次执行独占一个容器；正常、超时、OOM、断连、基础设施失败路径都在 `finally` 删除容器。
- 池容器不执行用户代码；取用后与冷创建容器走同一个 `SandboxContainerRunner` 执行路径。
- 审计仍是一执行一条 `exec_records`；池创建、补池、TTL 清理不写审计。
- SSE 契约、错误码、排队语义、断连语义均不变。

### 2. 容器工厂端口

新增 `SandboxContainerFactory` 端口，隔离 docker-java：

- `createPoolContainer()`：用与执行容器相同的隔离参数创建并启动一个 idle 容器，打 `role=pool` label。
- `createExecutionContainer(execId)`：冷创建路径，打 `exec-id` label。
- `isRunning(containerId)`：取池/巡检前的健康检查。
- `remove(containerId)`：强制删除并移除卷。
- `currentImageId()`：当前配置镜像的 ID，用于池容器新鲜度。
- `listPoolContainers()`：启动清理与失管巡检。

池管理器与 runner 只依赖这个端口；Docker 适配器保持薄。

### 3. 池管理器

- 配置：`target=12`、`max=16`、`idleTtl=10m`、`replenishWorkers=2`、`enabled=true`。
- 启动：监听应用就绪事件，异步填池到 target；填充期间来的执行直接冷创建。
- 取用：原子 checkout；取到后 `isRunning` 检查，失败则删除并继续取下一个或冷创建；池空不等待。
- 补池：checkout 后异步补到 target；最多 2 个并发创建；失败退避 1s→2s→5s，上限 30s；只记日志/指标。
- idle TTL：超过 10 分钟的 idle 容器删除并重建。
- 镜像新鲜度：启动记录镜像 ID；checkout/TTL 扫描时发现不一致，删除旧池容器。
- 关闭：应用关闭时删除所有 idle 池容器；执行中的容器由 runner 的 `finally` 删除。

### 4. Runner 接入

- `DockerSandboxContainerRunner` 不再自己创建执行容器；先从池取，池空则走冷创建。
- 取到的容器直接执行现有 `exec` 流程；执行结束仍 force remove。
- 冷创建保留现有隔离参数与 label 逻辑；池容器创建复用同一套 HostConfig。
- 执行容器的 `started`/`running` phase、stdout/stderr、stats、超时、OOM、abort 逻辑不变。

### 5. 并发与准入

- `globalConcurrency` 默认 8→12；`perUserConcurrency` 保持 2；`queueTimeout` 保持 120s。
- 准入仍同步获取 permit；不引入异步准入。
- Docker client `maxConnections` 从 `globalConcurrency + 4` 上调为覆盖执行 + 池上限 + stats/补池余量（至少 `globalConcurrency + poolMax + 8`）。

### 6. 降级与失败语义

- 池管理器不可用/池空/容器不健康/补池失败 → 冷创建。
- 冷创建失败 → 现有 `SANDBOX_UNAVAILABLE`，审计落 `error`。
- 池管理器异常不得向上抛到聊天主路径；只影响加速。
- `SANDBOX_POOL_ENABLED=false` 时完全不填池、不取池，行为与 M1 一致。

### 7. 生命周期与清理

- 池容器 label：`agent-core.sandbox=1`、`agent-core.sandbox.role=pool`；执行容器仍带 `agent-core.exec-id`。
- JVM 启动：列出 `role=pool` 容器并删除，再填池；不尝试接管上一进程的池。
- `SandboxMaintenance`：跳过 `role=pool`；只清理超过 2×idle TTL 的失管池容器；一次性容器规则不变。
- 池容器 idle 命令使用长 sleep（如 24h），避免自行退出。

### 8. 可观测

- 日志：池填充开始/完成、checkout 命中/未命中、冷创建兜底、补池失败、TTL/镜像清理、关闭清理。
- 指标：`sandbox_pool_idle`、`sandbox_pool_creating`、`sandbox_pool_checkout_total{result}`、`sandbox_pool_cold_fallback_total`、`sandbox_pool_replenish_failures_total`、`sandbox_container_lifecycle_ms`。
- 指标不带 userId/execId 标签，避免基数爆炸。

### 9. 配置

- `SANDBOX_POOL_ENABLED`（默认 `true`）
- `SANDBOX_POOL_TARGET`（12）
- `SANDBOX_POOL_MAX`（16）
- `SANDBOX_POOL_IDLE_TTL`（10m）
- `SANDBOX_POOL_REPLENISH_WORKERS`（2）
- `SANDBOX_GLOBAL_CONCURRENCY`（12）

测试上下文设置 `app.sandbox.pool.enabled=false`，避免 CI 启动额外容器。

### 10. 安全与容量

- 池容器与执行容器使用同一 HostConfig：`network none`、非 root、只读 rootfs、`/work` tmpfs、cap drop、pids/memory/CPU 限制、`no-new-privileges`。
- 池容器在执行前只运行 idle 进程，不包含用户代码或数据。
- 12 个 idle + 12 个执行中的 512MB 上限是设计目标；16GB 参考机之外的部署必须下调并发/池大小。
- 池不改变「执行间无残留」的承诺，因为容器仍是一次性的。

## Testing Decisions

好的测试验证外部行为：池的取用/补池/降级结果、容器是否被删除、隔离参数是否生效；不测 Docker 调用顺序或池内部字段。

### 1. 单元测试（fake factory）

- 填池到 target；不超过 max。
- checkout 返回 idle 容器；池空返回冷创建。
- 取到的容器不 running → 删除并取下一个/冷创建。
- 执行后容器被删除，不回流。
- 补池失败退避；恢复后继续补。
- idle TTL 到期删除；镜像 ID 变化删除。
- 关闭时删除 idle 池容器。
- `SANDBOX_POOL_ENABLED=false` 不创建池、直接冷创建。

### 2. Docker 集成测试

- 池容器与执行容器隔离参数一致：非 root、禁网、只读 rootfs、`/work` tmpfs、cap drop。
- 一次执行独占一个容器：记录 checkout 的容器 ID，执行后不存在；下一次执行使用不同容器。
- 池容器不会被 `SandboxMaintenance` 按一次性容器规则误杀。
- JVM 重启后旧池容器被清理并重新填池。
- 审计行与容器执行一一对应。

### 3. 压测对照

- 同一组 20 个 run_code 并发 + 180 chat，5 实例或单实例按目标环境；分别 `pool off` 与 `pool on`。
- 目标：pool on 工具回传 **P50 ≤ 10s、P95 ≤ 25s**；pool off 记录基线。
- 运行前等池填满（指标/日志）；运行后统计遗留执行容器数，必须为 0；池 idle 容器数回到 target。
- 记录队列等待、容器生命周期、冷创建兜底次数。

### 4. 回归

- 现有 sandbox 集成测试（网络失败、超时、OOM、只读 rootfs）保持绿色。
- 准入/排队/审计/断连测试不回归。
- 契约 fixture 不变。

## Out of Scope

- 同容器串行复用、每用户长驻容器。
- 异步准入 / 更大规模排队。
- gVisor / runsc。
- 多语言沙箱。
- AIO Sandbox（ADR-0003）。
- 跨主机的沙箱调度。

## Further Notes

- 目标 P50/P95 是 16GB 参考机上的验收线；如果未达成，先调池 target / 全局并发，不退回容器复用。
- 池的 idle 内存是上限而非预分配，但仍需在部署文档中给出 16GB 参考配置。
- 若后续引入 gVisor，池容器与执行容器需同时切换 runtime，不能只改执行路径。
- 本轮不改数据库、不改契约；配置回退即可恢复 M1 行为。
