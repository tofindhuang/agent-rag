# agent-core

![CI](https://github.com/tofindhuang/agent-core/actions/workflows/ci.yml/badge.svg)

基于 [pi](https://github.com/earendil-works/pi-coding-agent) 为底层 agent 引擎的对话级知识库系统。

核心能力：多轮对话（流式）、知识库检索（RAG）、沙箱代码执行、上下文自动压缩（compaction）、用户习惯长期记忆。

- 需求文档：[docs/requirements.md](docs/requirements.md)
- 当前分支：`dev`（功能完成后合并请求回 `main`）

## 结构

```
├── docs/              需求文档、specs、tickets、ADRs
├── contract/          Java↔gateway 契约（v1 事件、错误、fixture）
├── gateway/           Node 网关：托管 pi AgentSession，注册 kb_search / run_code 工具
├── server/            Java 21 + Spring Boot 3：业务编排、RAG、沙箱调度（M1）
├── web/               Next.js 前端：聊天界面，SSE 流式渲染
├── sandbox/           沙箱执行镜像（Python 3.12，隔离参数见 Dockerfile 注释）
├── .pi/skills/        开发流程 skills（见 AGENTS.md 规范流程）
├── AGENTS.md          模块地图、命令、编码规范、文档约定（agent 必读）
├── CONTEXT.md         领域词汇表
└── docker-compose.yml MySQL 8 + Redis 7 + Qdrant + RabbitMQ + Silo（S3 兼容）
```

规范开发流程（在 pi 中自动发现，也可 `/skill:<name>` 显式调用）：**`/skill:grilling`（Phase 1，强制）** → `/to-spec` → `/to-tickets` → `/implement`（配合 `/tdd`）→ `/code-review` → `/skill:pre-push-checks` → push。新功能/需求、架构组件、跨模块改动、schema/迁移、外部集成不得跳过 Phase 1，详见 `AGENTS.md`。

## 运行（开发）

前置：Node 22+（gateway 依赖 pi SDK 的 `fs.globSync`）、Java 21+、Docker（仅基础设施与沙箱需要）。

```bash
# 0. 基础设施：MySQL/Redis(:3306/:6379)、Qdrant(:6333)、
#    RabbitMQ(:5672/:15672)、Silo S3(:9000/:9001)
docker compose up -d

# 1. 网关（端口 3001）
cd gateway && npm install && npm run dev

# 2. 业务层（端口 8080）
cd server && mvn spring-boot:run

# 3. 前端（端口 3000）
cd web && npm install && npm run dev
```

模型凭证：gateway 复用 pi 的本机凭证存储（`~/.pi/agent/`），开发机用 `pi auth` 配置过即可，无需额外环境变量。

## 已验证（骨架阶段）

- gateway：类型检查通过；冒烟验证了 prompt 整体替换（`systemPromptOverride`）与工具白名单（仅 `kb_search` / `run_code`，内置工具全部禁用）
- gateway：端到端真实 LLM 调用，SSE 事件流（`text_delta` → `agent_settled` → `prompt_settled`）完整
- server：`mvn -q verify` 通过，启动冒烟验证了健康检查与统一错误 envelope
- web：`npm run typecheck` 与 `npm run build` 通过，`next start` 冒烟返回 200
- RAG spike：Qdrant + SiliconFlow 的摄取、检索、rerank、隔离与故障注入全部通过，详见 `docs/research/2026-10-23-rag-selection-spike.md`
- CI：`.github/workflows/ci.yml` 的 contract / gateway / server / web / sandbox 五个 job 全绿

## M0 收尾

已收敛为「契约 v1 + RAG 选型 spike」：设计见 [docs/specs/2026-10-23-m0-closeout.md](docs/specs/2026-10-23-m0-closeout.md)，契约见 [contract/v1/README.md](contract/v1/README.md)，拆票见 [docs/tickets/m0-closeout/](docs/tickets/m0-closeout/)。

RAG spike 已完成：Qdrant + SiliconFlow `BAAI/bge-m3` / `BAAI/bge-reranker-v2-m3`，rerank 候选 10；实测与限制见 [docs/research/2026-10-23-rag-selection-spike.md](docs/research/2026-10-23-rag-selection-spike.md)。

M0 收尾已完成：契约 v1、gateway/Java/web 消费、RAG 选型与 CI 全绿。下一步进入 M1：持久化与认证、RAG 摄取、沙箱执行器（见需求文档 §7）。
