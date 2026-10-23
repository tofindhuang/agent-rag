# gateway 冷启动用 pi SessionManager 本地文件 + sidecar 归属元数据

Date: 2026-10-23

## Status

Accepted

## Context

M0 骨架的 gateway 用 `SessionManager.inMemory()`，进程重启后所有引擎上下文丢失；Java 侧的 userId→session 归属也是内存 Map。FR-1 要求「gateway 重启后活跃会话可恢复继续对话」。

ADR-0006 已经定了原则：pi session 存储是引擎上下文的唯一来源，不从 MySQL 消息投影重放（投影缺 entry 树、compaction 边界、工具调用结构，重放必然失真）。剩下的问题是：pi session 文件放哪种介质、重启后归属校验怎么做、Java 何时触发恢复。

pi SDK 的 `SessionManager` 原生支持 `create(cwd, sessionDir, {id})`、`findById(cwd, id, sessionDir)`、`open(path, sessionDir)`——按 sessionId 落文件、重启后按 ID 找回是现成能力，不需要自造存储。M1 是单实例部署。

## Decision

- **本地文件目录**：`GATEWAY_SESSION_DIR` 环境变量指定（默认 gateway 工作目录下 `data/sessions`），pi session 以 JSONL 落盘。
- **create-or-open 幂等恢复**：`PUT /sessions/:id` 先按 id 查找；存在则 `open`（响应 restored 语义），不存在则 `create(..., {id})`。session id 由 Java 生成（= 业务 Session id = AgentSession id）。
- **sidecar 归属元数据**：每个 session 旁写 `${id}.meta.json` 记录 `userId`；重启后 gateway 仍能校验「这个会话属于谁」。session 文件在而 sidecar 缺失时，按当次 `X-User-Id` 绑定写入并告警。
- **Java 每次发 prompt 前先发幂等 `PUT /sessions/:id`**：单实例 50 会话规模下这次额外 HTTP 成本可忽略，换来「任何时刻 gateway 重启都能在下一条消息前恢复」的必然性，不需要 404 重试逻辑。
- **M1 不做文件清理与多实例**：session 文件无限保留；共享存储（对象存储/共享文件系统）与清理任务留 M2。

## Considered Options(被否的备选)

- **直接上 S3/MinIO 共享对象存储** — 多实例友好，但要处理锁、原子写、缓存、失败重试；M1 单实例下是纯运维成本，M2 需要时再换，契约不变。
- **从 MySQL 消息投影重放重建上下文** — 看似单一真相源，实则缺 pi 的 entry 树、compaction 边界、token 账与工具调用原始结构；重放失真且要伪造 entry，ADR-0006 已否。
- **把 pi session entries 塞进 Redis/MySQL 自造 session store** — 等于绕开 SDK 稳定面自己维护一套存储格式，pi 升级即碎。
- **仅在 404 时恢复（不主动 PUT）** — 省一次调用，但要处理「404 → PUT → 重试」的状态机，且重启后第一条消息多一跳延迟；主动幂等 PUT 更简单可预期。

## Consequences

- gateway 换宿主/换机器时 session 文件不迁移，会话不可恢复（单实例 M1 接受；M2 共享存储解决）。
- 磁盘占用随会话单调增长，M1 明确不清理，挂账 M2。
- 归属校验依赖 sidecar 文件；sidecar 丢失会退化为「首次访问者绑定」，需要日志告警暴露异常。
- Java 侧内存 `sessionUsers` Map 删除，归属真相转移到 MySQL sessions 表（Java 校验）+ sidecar（gateway 校验），两侧一致性由同一 sessionId 串联。
- pi SessionManager 文件格式随 SDK 版本演进，锁版本（ADR-0001）+ 契约测试继续是升级安全网。
