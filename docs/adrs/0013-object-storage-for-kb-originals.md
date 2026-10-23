# 知识库原文存对象存储（MinIO / S3 兼容）

Date: 2026-10-23

## Status

Accepted（修订 `docs/requirements.md` FR-2 的「元数据与原文入 MySQL」）

## Context

FR-2 原草案要求「元数据与原文入 MySQL」。实现前评估发现：

- 单用户配额为 1 GB 原文，MySQL `LONGBLOB` 会让备份、连接池与表体积一起变重；
- 原文是最冷的数据，解析后只用于重建索引，不需要参与业务查询；
- 删除/重建/配额统计需要可靠的对象生命周期，而 MySQL 行删除无法回收已占用的页；
- 本地与离线部署不能强依赖云对象存储。

同时，`docs/adrs/0006` 已确立「MySQL 存业务真相、pi 文件存引擎上下文、Redis 只做加速」的分工；原文属于「大块冷数据」，适合独立存储边界。

## Decision

- 原文（PDF/DOCX/MD/TXT/HTML 快照）存 **MinIO**，通过标准 S3 API 访问；`docker-compose` 固定版本并挂数据卷，未来换云 S3 只改配置。
- MySQL 只存文档元数据、状态与切片文本；Qdrant 存向量与检索 payload。
- 对象键 `{userId}/{documentId}/{sha256}.{ext}`，桶默认私有，M1 只允许 server 读写（不签 presigned URL、不暴露桶）。
- 删除顺序：对象（幂等）→ Qdrant 点 → MySQL 行；对象删除失败保持 `deleting` 并重试，避免「行没了文件还在」。
- 上传流：multipart 落临时文件 → 计算 sha256 → 上传对象 → 插入文档行；不把 20 MB 读进堆内存。
- 配额（200 篇 / 1 GB）按对象存储用量统计，删除即释放。

## Considered Options（被否的备选）

- **继续用 MySQL LONGBLOB** — 备份与连接池成本随用户量线性变差，且无法独立清理大对象。
- **本地文件系统卷** — 实现最简单，但多实例、迁移与备份都要自己造轮子，且没有 S3 的幂等/元数据能力。
- **直接云 S3** — 运维最省，但本地开发与离线部署被云厂商绑定，违反自托管目标。
- **只存解析后的纯文本** — 省空间，但重建索引时无法回到原始格式（页码、表格），与「重新处理」语义冲突。

## Consequences

- 多一个必须常驻的组件（MinIO）与数据卷备份项；本地开发 `docker-compose` 多一个容器。
- 跨存储一致性由「先写对象、后写 MySQL；删除先对象后行」的状态机兜底，失败可重试。
- `docs/requirements.md` 的 FR-2 与数据模型说明需要同步修改。
