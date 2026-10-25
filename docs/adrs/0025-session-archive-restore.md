# 会话冷归档到对象存储，由 Java 代理字节流并保留可续聊

Date: 2026-10-25

## Status

Accepted

## Context

ADR-0008 规定 pi session 文件无限保留，清理留到 M2；`SessionPool` 只释放内存，从不删除磁盘文件。目标规模是约 2000 注册用户，JSONL 随对话单调增长，共享目录的本地磁盘没有上界。

会话文件与业务消息的语义不同：

- MySQL `messages` 投影服务历史展示，删除引擎文件后历史仍可看；
- **pi session 是引擎上下文的唯一来源**（ADR-0006），文件一旦丢失，会话就不能继续对话；
- 用户「永久删除」已经会删除文件（ADR-0011），但那是用户主动、不可恢复的操作。

因此需要一种「本地不常驻、但仍可续聊」的保留方式。对象存储已经作为知识库原文的存储（ADR-0013），但 ADR-0013 明确「只有 server 读写桶、不签 presigned URL」，gateway 今天没有任何 S3 凭证。

## Decision

### 1. 归档条件与保留

- Java 每日定时任务扫描 `sessions.last_active_at`，超过 `SESSION_ARCHIVE_AFTER_DAYS`（默认 90 天，`0` 关闭）未活动的会话进入归档。
- 归档对象永久保留，不做对象存储生命周期删除；只有用户永久删除会话时才删除归档。
- 归档后 MySQL 消息投影保持不变；用户查看历史不触发恢复，只有下一次 prompt 需要引擎上下文时才恢复。

### 2. 字节流边界

- **Java 代理归档/恢复字节流**：gateway 把 gzip 后的 JSONL 与 sidecar 上传到 Java 内部端点，由 Java 写入对象存储；恢复时 gateway 从 Java 流式读取。
- gateway 不持有任何 S3 凭证；ADR-0013 的「只有 server 读写桶」保持不变，不引入 presigned URL。
- 对象键：`sessions/{userId}/{sessionId}/session.jsonl.gz` 与 `sessions/{userId}/{sessionId}/session.meta.json`；对象元数据记录 sha256 与字节数。

### 3. 归档流程

1. Java 定时任务按阈值选出候选会话，调用任一存活 gateway 的 `POST /sessions/:id/archive`。
2. gateway 以非阻塞方式获取该会话的文件锁；锁被占用（会话驻留或在飞）则跳过，不归档。
3. gateway 读取 JSONL 与 sidecar，gzip JSONL，计算 sha256，上传到 Java。
4. Java 校验 sha256、写入对象存储、更新 `session_archives` 账本为 `archived`。
5. gateway 只在 Java 返回成功后删除本地 JSONL 与 sidecar，并释放锁。
6. 任一步失败：本地文件保留，账本记 `failed`、`attempts` 与 `last_error`，次日重试；绝不出现「本地已删、归档没成」。

### 4. 恢复流程

1. Java 在每次 prompt 前仍调用 `PUT /sessions/:id`，并带 `requireExisting`（会话已有消息时为 true）。
2. gateway 发现本地无文件时，向 Java 请求归档对象；校验 sha256 后写入临时文件并原子 rename，再按既有逻辑打开。
3. 归档缺失或校验失败返回 `409 SESSION_ARCHIVE_UNAVAILABLE`（可重试）；`requireExisting=true` 时**绝不静默创建空会话**。
4. 恢复后本地文件继续作为主存储；下一次超过阈值时重新归档（覆盖旧对象）。

### 5. 账本与删除

新增 MySQL 表 `session_archives`（up-only 迁移）：

- `session_id` 主键、`user_id`、`object_prefix`
- `jsonl_sha256`、`meta_sha256`、`size_bytes`
- `status`（`archived` / `failed` / `deleted`）、`attempts`、`last_error`
- `archived_at`、`updated_at`

永久删除顺序：删除归档对象（幂等）→ 通知 gateway 删除本地文件 → 删除 MySQL 行。对象删除失败时保留账本并允许重试，不先删业务行。

### 6. 可观测

归档与恢复记录结构化日志：sessionId、userId、字节数、耗时、重试次数、失败原因；不记录对话正文。归档账本同时是运维排查与指标的数据源。

## Considered Options(被否的备选)

- **永久保留本地文件，只加磁盘监控** — 实现最省，但 2000 用户下本地磁盘没有上界，迟早要处理；归档账本与恢复语义仍要做。
- **过期直接删除引擎文件，保留 MySQL 历史** — 磁盘可控，但会不可逆地让老会话无法续聊，用户不可预期。
- **把 JSONL 增量写进对象存储** — 对象存储不支持低成本追加，每次都要整文件读改写；pi 的 append-only 语义与 S3 不匹配。
- **Java 直接读写共享目录** — 省一次字节流代理，但破坏 ADR-0006 的存储归属，Java 要挂 gateway 的卷，耦合更重。
- **gateway 持有 S3 凭证直连** — 少一跳，但把对象存储凭证扩散到 Node 服务，破坏 ADR-0013 的凭证边界。
- **presigned URL** — 字节不经过 Java，但需要修改 ADR-0013 的明确决策，并为低频归档扩大桶的访问面。
- **按磁盘水位触发归档** — 按实际压力动作，但行为不可预测、可能一次归档一大批；时间阈值更稳定，磁盘告警作为补充。

## Consequences

- 会话首次在归档后被继续时，prompt 前多一次下载与校验；需要在 UI 或超时语义上容忍这段延迟。
- 归档任务依赖 Java 定时任务与 gateway 同时可用；Java 停机期间不归档，磁盘继续增长，恢复后按阈值补做。
- 新增 `session_archives` 表与一条迁移；归档对象不自动过期，对象存储总量随用户历史单调增长。
- 归档对象包含完整对话与工具输出，安全边界与本地 JSONL 相同；桶保持私有，访问只经 Java，备份与磁盘/对象存储加密仍是部署方责任。
- 归档失败只重试、不阻塞用户；账本可能保留已失效的 `failed` 行，需要清理策略。
- 恢复失败必须是明确可重试错误，不能退化成新建空会话；这需要 gateway 与 Java 共同遵守 `requireExisting` 契约。
