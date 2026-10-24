# FR-3 沙箱执行器（run_code）

## Problem Statement

模型在对话中无法执行代码：`run_code` 回调目前返回 `501 NOT_IMPLEMENTED`。需要计算、验证或处理数据的用户只能离开对话自己做，模型也无法用执行结果自证。与此同时，运维侧没有执行审计（谁、在哪个会话、跑了什么代码、消耗多少资源、结果如何），也没有并发保护——一旦放开执行，单个用户可以无限制地占用宿主机。

## Solution

打通 `run_code` 端到端链路，严格实现契约 v1 §8 的流式 `run_code`：

- gateway 的 `run_code` 工具以 SSE 消费 Java `POST /api/exec`，把 `phase` / `stdout` / `stderr` 帧转成 `tool_progress`（仅展示），把 `result` 帧转成最终 `tool_result`。
- Java 先做执行准入（per-user + 全局可过期 permit，超限排队，排队超时返回可重试错误），再为每次执行创建一次性 Docker 容器（固定镜像、禁网、资源受限、非 root、只读 rootfs、tmpfs 工作目录），代码写入 tmpfs 后执行，流式回传 stdout/stderr，超时或客户端断连即杀容器。
- 每次执行写入 `exec_records` 审计行（完整代码、触发身份、资源消耗、终态），JVM 崩溃后的孤儿容器与 `running` 记录由启动/周期巡检回收，审计保留 90 天。
- Docker、镜像或 Redis 不可用时只有 `run_code` 不可用，聊天与知识库不受影响。

## User Stories

1. As a 用户, I want 模型能在对话里执行 Python 并看到 stdout、stderr 与退出码, so that 我不用离开对话验证计算或数据处理结果。
2. As a 用户, I want 网络请求在沙箱内失败并把错误正常回传给模型, so that 执行代码无法外联或泄露数据。
3. As a 用户, I want 死循环或长任务在超时阈值后被杀死并收到超时说明, so that 会话不会挂死、宿主机不被拖垮。
4. As a 用户, I want 超长输出被截断并明确标注, so that 模型上下文不被撑爆、回传仍然可读。
5. As a 用户, I want 停止生成时正在执行的容器立刻被杀, so that 不再消耗算力。
6. As a 用户, I want 同一用户并发执行超过上限时请求排队而不是直接报错, so that 连续的工具调用不会因瞬时并发失败。
7. As a 用户, I want 排队等待超时后收到明确且可重试的错误, so that 我知道稍后再试而不是以为代码有问题。
8. As a 用户, I want 容器因内存超限被杀时收到明确提示, so that 我知道应该降低内存占用而不是重试同样的代码。
9. As a 用户, I want 请求非 Python 语言时收到清晰拒绝, so that 我知道 M1 只支持 Python。
10. As a 用户, I want 提交超长代码时被拒绝并说明上限, so that 请求大小可控。
11. As a 审计者, I want 每次执行都能查到完整代码、触发用户/会话/轮次、资源消耗与终态, so that 可以追溯与排障。
12. As a 运维者, I want JVM 崩溃后遗留的容器与 `running` 审计行被自动回收, so that 宿主机和审计表不会积累垃圾。
13. As a 运维者, I want 审计记录按保留期自动清理, so that 存储成本可控。
14. As a 运维者, I want Docker daemon 或镜像不可用时只有 `run_code` 返回明确错误, so that 聊天与知识库继续可用。
15. As a 运维者, I want Redis 不可用时执行快速失败, so that 不会出现绕过准入的并发执行。
16. As a 模型, I want 执行进度只作为展示信息、不进入我的上下文, so that 工具结果保持简洁。
17. As a 前端用户, I want 工具执行期间能看到分阶段的流式输出, so that 长执行不会看起来卡死。
18. As a 开发者, I want 失败路径使用稳定错误码与可重试标记, so that gateway 与前端能做一致的降级。
19. As a 安全负责人, I want 容器非 root、只读根文件系统、禁网、capability 全 drop、pids 受限, so that 沙箱逃逸面最小。
20. As a 用户, I want 彻底删除会话时相关执行记录一并删除, so that 「彻底删除」名副其实。
21. As a 用户, I want 每次执行使用独立容器且结束即销毁, so that 不同执行之间不残留文件或状态。
22. As a 模型, I want 超时、OOM、基础设施失败都以文本形式进入工具结果, so that 我能向用户解释失败原因。
23. As a 用户, I want 同一会话内的多次执行互不阻塞（在并发上限内）, so that 工具循环可以连续工作。
24. As a 开发者, I want 执行器的 Docker 交互与准入逻辑可替换、可测试, so that 单测不依赖真实 Docker。

## Implementation Decisions

### 契约与帧

- `POST /api/exec`，请求体 `{ language, code, timeoutMs }`，身份只从 gateway 注入的 header 读取（现有 `ToolIdentity`）。
- 响应为 SSE：`event: <name>\ndata: <单行 JSON>`，**不带 `id` / `seq`**（契约 `run_code` 段未定义，不发明）。
- 帧序列：
  - `started { execId }`：准入通过且审计行已插入后立即发送。
  - `phase { phase }`：`starting`（创建容器）、`running`（容器已启动）。
  - `stdout { chunk }` / `stderr { chunk }`：单块 ≤ 2048 UTF-8 字节，按字符边界切分。
  - `result { exitCode, durationMs, timedOut, truncated }`：必须出现且只出现一次，之后立即关闭流。
- 每 15 秒发送 `: ping` 注释帧，不占事件、不改变帧语义。
- 响应头：`text/event-stream; charset=utf-8`、`cache-control: no-cache, no-transform`、`x-accel-buffering: no`。
- 流开始前的失败（参数、排队超时、Redis/Docker 不可用、审计写失败）走统一 JSON 错误 envelope；流开始后的基础设施失败不再有 JSON 错误，改为一条合成 `stderr` 帧 + 唯一的 `result` 帧（`exitCode = -1`），审计记 `error`。

### 准入与排队

- permit 在创建 `SseEmitter` 之前获取：HTTP 请求表现为等待，拿到许可后才写 `200` 与 `started`。这样排队超时仍能返回合法错误 envelope。
- 两层上限：per-user 默认 2（key `sem:sandbox:{userId}`），全局默认 8（key `sem:sandbox:global`）。加锁顺序固定为**先 per-user、后 global**，避免排队请求占着全局名额饿死其他用户。
- 排队等待上限默认 120s；超时返回 `503 SANDBOX_QUEUE_TIMEOUT`（`retryable: true`）。
- permit 使用 Redisson `RPermitExpirableSemaphore`，lease 默认 240s（> 排队上限 120s + 执行上限 60s + 余量），JVM 崩溃后自动过期，不会永久泄漏名额。lease 从获取时开始计，因此必须覆盖「等全局 permit + 执行」的最坏路径。
- Redis 不可用：快速失败 `503 UPSTREAM_UNAVAILABLE`，不做进程内降级。
- 排队请求会占用 Tomcat 线程（≤120s）；M1 单实例可接受，列为已知限制，扩容时改为异步准入。

### 执行器

- 使用 docker-java（需求技术表已定），连接默认跟随 `DOCKER_HOST`，否则 unix socket。
- 固定镜像 `agent-core-sandbox:python-3.12`；服务端不自动 build/pull，缺失时启动只打 ERROR 日志，执行返回 `503 SANDBOX_UNAVAILABLE`。
- 每次执行一个容器，流程：创建（PID1 为 idle 进程）→ 启动 → `docker exec` 执行 `sh -c 'head -c <N> > /work/main.py && exec python /work/main.py < /dev/null'`，代码经 exec stdin 精确写入 N 字节 → 跟随 exec 输出流采集 → 等待退出 → 强制删除。
  - 不走 `docker cp`：只读 rootfs 会让 Docker 直接拒绝复制（即使目标是 tmpfs）。
  - 不走容器 stdin（`python -`）：docker-java 的 attach 不会半关连接发送 EOF，`python -` 会永远等输入；`head -c N` 只读精确字节数，不依赖 EOF。
  - `python` 的 stdin 重定向到 `/dev/null`，用户代码里的 `input()` 立即得到 EOF 而不是挂住。
- 隔离参数（在镜像注释基础上补齐）：`--network none`、`--memory 512m`、`--memory-swap 512m`（禁 swap）、`--cpus 1`、`--pids-limit 64`、`--read-only`、`--tmpfs /work:size=64m,uid=1000,gid=1000`、`-u 1000:1000`、`--cap-drop ALL`、`--security-opt no-new-privileges`、`--init`。
- 容器 label：`agent-core.sandbox=1`、`agent-core.exec-id=<execId>`，供巡检识别。
- 超时：`timeoutMs` 默认 30000，服务端 clamp 到 `[1000, 60000]`；从 exec 启动开始计时。到点 SIGKILL，`exitCode = 137`、`timedOut = true`。create/exec 阶段失败算基础设施错误，不算超时。
- 断连：`SseEmitter` 的 `onCompletion`（非正常完成时）/`onError`/`onTimeout` 触发取消 → SIGKILL 容器 → 审计 `aborted`；用完成标记避免正常结束时误判。
- 容器清理：正常/异常路径都在 `finally` 中强制删除；启动时与每分钟巡检带 `agent-core.sandbox=1` 且超过 `maxTimeout + 余量` 的遗留容器。

### 输出与终态

- stdout + stderr **合计**上限 64 KiB；达到上限后停止向下游转发，但继续 drain 容器输出，避免阻塞；`result.truncated = true`。
- 输出顺序不做跨流保证；stdout/stderr 各自保序。
- 终态映射：
  - 退出码 0 → `succeeded`
  - 退出码非 0 → `failed`
  - 超时被杀 → `timed_out`（`exitCode = 137`）
  - 客户端断连 → `aborted`（无 result 帧）
  - 基础设施失败 → `error`
- OOM 由 `docker inspect` 的 `State.OOMKilled` 识别：审计 `failed` + `oom_killed = true`，追加合成 stderr `container killed: out of memory`（计入 64 KiB），`timedOut = false`。

### 审计

- 新表 `exec_records`（up-only 迁移，`CREATE TABLE IF NOT EXISTS`，utf8mb4）：

```sql
CREATE TABLE exec_records (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id CHAR(36) NOT NULL,
    turn_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NULL,
    language VARCHAR(32) NOT NULL,
    code MEDIUMTEXT NOT NULL,
    status ENUM('running','succeeded','failed','timed_out','aborted','error') NOT NULL,
    exit_code INT NULL,
    duration_ms INT NULL,
    timed_out BOOLEAN NOT NULL DEFAULT FALSE,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    oom_killed BOOLEAN NOT NULL DEFAULT FALSE,
    peak_memory_bytes BIGINT NULL,
    cpu_time_ms BIGINT NULL,
    stdout MEDIUMTEXT NULL,
    stderr MEDIUMTEXT NULL,
    error_message VARCHAR(1024) NULL,
    image VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_exec_user_created (user_id, created_at),
    KEY idx_exec_session_created (session_id, created_at),
    KEY idx_exec_status_created (status, created_at),
    CONSTRAINT fk_exec_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_exec_session FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- `session_id` 级联删除：会话彻底删除（ADR-0011）时执行记录一并删除，符合「彻底删除」语义。
- 写入时机：准入通过后先插 `running`，执行结束一条 UPDATE 落终态；审计插入失败 → 拒绝执行（fail closed），释放 permit 并返回 `503`。
- 资源消耗：执行期间每秒采样 docker stats，记录峰值内存与累计 CPU 时间；不用于精确计费。
- 崩溃回收：启动时把超过 `maxTimeout + 余量` 仍为 `running` 的行标记为 `aborted`。
- 保留期：默认 90 天，按 `created_at` 每日清理；需求 NFR 的「沙箱执行产物保留 7 天」解释为执行产物/文件，不适用于审计账本。

### 错误码（新增，纯增量）

| code | HTTP | retryable | 场景 |
|---|---|---|---|
| `SANDBOX_UNAVAILABLE` | 503 | true | Docker daemon 不可达或镜像缺失 |
| `SANDBOX_QUEUE_TIMEOUT` | 503 | true | 排队超过上限 |
| `SANDBOX_CODE_TOO_LARGE` | 413 | false | 代码超过 256 KiB |

非 Python 语言、空白代码 → 现有 `INVALID_REQUEST`；Redis 不可用 → 现有 `UPSTREAM_UNAVAILABLE`。

### 配置

前缀 `app.sandbox.*`，全部可用环境变量覆盖：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `docker-host` | `DOCKER_HOST` 或 unix socket | Docker 连接 |
| `image` | `agent-core-sandbox:python-3.12` | 执行镜像 |
| `default-timeout` | `30s` | 默认执行超时 |
| `max-timeout` | `60s` | 超时上限 |
| `code-max-bytes` | `262144` | 代码上限 |
| `output-max-bytes` | `65536` | stdout+stderr 合计上限 |
| `chunk-max-bytes` | `2048` | 单帧输出上限 |
| `per-user-concurrency` | `2` | 每用户并发 |
| `global-concurrency` | `8` | 全局并发 |
| `queue-timeout` | `120s` | 排队等待上限 |
| `permit-lease` | `240s` | permit 自动过期 |
| `retention-days` | `90` | 审计保留期 |

### gateway

- `run_code` 改为流式消费 SSE：`phase` / `stdout` / `stderr` 通过 `onUpdate` 发 `details.progress = { kind, phase?, chunk? }`（契约 §6 形状）；本地累积截断后的输出，收到 `result` 后拼最终 `tool_result` 文本。
- 最终 `tool_result.details` 只放 `{ execId, exitCode, durationMs, timedOut, truncated }`，避免超过契约的 8 KiB details 上限；正文走 `content`。
- `signal` 中断 → fetch 断开 → Java 感知断连并杀容器，语义天然对齐。
- 非 2xx 响应按错误 envelope 解析并保留 `code`；SSE 解析需容忍注释心跳、跨 chunk 拆帧与 UTF-8 边界。

### 部署与安全边界

- M1 Java 跑在宿主机上，直接访问 `/var/run/docker.sock`；若未来容器化，挂载 socket 等价 root 权限，必须限制在受信宿主机。不引入远程 daemon / TLS。
- 沙箱镜像内精确锁 `requests==2.32.3`，使 AC1 的 `requests.get(...)` 能真实体现网络隔离，而不是先报 `ModuleNotFoundError`。
- 隔离 flag 的权威清单同步更新到 `sandbox/Dockerfile.python` 注释与 `sandbox/README.md`（命令行改为 exec + `head -c` 投递）。

## Testing Decisions

- 只测外部行为：HTTP/SSE 帧、工具输出、审计行、容器可见的隔离效果；不测 Docker 内部调用顺序。
- 测试缝（按最高层优先）：
  1. `SandboxService` + `ExecEventSink`：用 fake `SandboxAdmission` / `SandboxContainerRunner` 覆盖排队超时、截断、超时、abort 竞态、状态流转、代码超限。
  2. 真实 Docker 集成测试（`@Tag("sandbox")`，CI 构建镜像后运行）：AC1 网络失败、AC2 死循环超时、AC3 审计完整、只读 rootfs/非 root 复核；OOM 用例允许标记 flaky。
  3. AC4 排队：Redis Testcontainers + 阻塞 fake runner，断言第二个同用户请求排队后成功而非报错。
  4. gateway：本地假 SSE 服务器，覆盖拆帧、注释心跳、错误 envelope、abort，并断言 `onUpdate` 进度与最终文本。
  5. Controller 层：MockMvc async dispatch 断言 SSE 头与帧序，错误场景断言 JSON envelope。
- 先例：`AbstractIntegrationTest` / `AbstractKbIntegrationTest`（Testcontainers）、`FakeGateway`、`SseFrameParser`、gateway 契约测试。

## Out of Scope

- 多语言执行（O3）、gVisor/runsc 加固（P2）、远程 daemon / K8s 执行。
- AIO Sandbox / agent 工作区（O4，ADR-0003 已否决作为 run_code 底座）。
- 沙箱产物文件持久化、下载、保留与清理（仅审计行有保留期）。
- 执行记录查询 UI / API（本 spec 只落库）。
- 排队进度可视化（不新增 `queued` phase）。
- 交互式 stdin、TTY、容器复用、网络 allowlist。
- actuator health 端点。
- 精确计费与 CPU/内存配额之外的成本核算。

## Further Notes

- 契约更新属纯增量：在 `contract/v1/README.md` 的 `run_code` 段补充上述三个错误码；不升主版本，不需要新事件。gateway 原 `SandboxExecResponse` 缓冲接口删除。
- AC 映射：AC1 → 网络隔离 + 集成测试；AC2 → 超时杀容器 + 集成测试；AC3 → `exec_records` 完整代码/资源消耗/终态；AC4 → per-user 排队 + Redis 集成测试。
- 已知限制：排队占用 Tomcat 线程；M1 单实例、默认 8 全局并发；多实例部署时 permit 语义仍成立，但 Tomcat 线程模型需要重新评估。
- permit lease 曾误定为 150s，按「排队 120s + 执行 60s」修正为 240s，避免等全局 permit 期间 per-user permit 提前过期。
