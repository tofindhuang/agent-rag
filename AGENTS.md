# AGENTS.md

This file provides guidance to AI coding agents working in this repository.

## Project Overview

agent-core 是基于 [pi](https://github.com/earendil-works/pi-coding-agent) 为底层 agent 引擎的对话级知识库系统。需求与架构见 [docs/requirements.md](docs/requirements.md)（唯一权威来源）。

职责边界（一句话版）：**Java 永远不直接调用 LLM；gateway 是唯一的模型通路；pi 不做业务。**

## Module Map

| 模块 | 栈 | 职责 |
|---|---|---|
| `gateway/` | Node 22+ / TypeScript / pi SDK | 托管 AgentSession，SSE 事件流透传，kb_search / run_code 工具 |
| `server/` | Java 21 / Spring Boot 3 | 认证、RAG 管线、沙箱调度、画像记忆、业务编排 |
| `web/` | Next.js / React | 聊天 UI、知识库与记忆管理界面 |
| `sandbox/` | Docker 镜像 | 隔离代码执行（无网络、资源受限、非 root、只读根） |
| `ocr/` | Python 3.12 / FastAPI / RapidOCR | 扫描版 PDF 的逐页 OCR 侧车（内部 HTTP，ADR-0014） |
| `contract/` | — | Java↔gateway 契约 v1、错误码与共享 fixture |
| `docs/` | — | 需求文档、specs、tickets、ADRs |

### Per-module commands

```bash
# gateway / web
cd gateway && npm run typecheck     # web 同名
cd gateway && npm run dev           # :3001；web 为 :3000

# server（需 JDK 21）
cd server && mvn -q compile
cd server && mvn spring-boot:run    # :8080

# ocr（可选；扫描版 PDF 兜底）
cd ocr && pytest

# infra
docker compose up -d                # mysql + redis
```

模型凭证：由用户在应用内配置 provider（API Key 加密入库，ADR-0010）；gateway 仅在会话内存中使用，不写入仓库或 `.env`。

## Documentation conventions

| 内容 | 位置 | 由哪个 skill 产出 |
|---|---|---|
| 需求 | `docs/requirements.md` | — |
| Specs（跨模块） | `docs/specs/YYYY-MM-DD-<slug>.md` | `/to-spec` |
| Specs（模块内） | `<module>/docs/specs/` | `/to-spec` |
| ADRs | `docs/adrs/`（跨模块）、`<module>/docs/adrs/`（模块内） | `/domain-modeling` |
| 领域词汇表 | `CONTEXT.md` | `/domain-modeling` |
| 研究/选型记录 | `docs/research/YYYY-MM-DD-<slug>.md` | `/research` |

工单、决策地图与验收记录由维护者内部保留，不随公开仓发布。

Agent skills（`.pi/skills/`、`.agents/skills/`）由维护者私有维护，不随公开仓发布；下文出现的 `/skill:<name>` 仅为工作流名称。

## SDD Development Process（强制）

所有新功能、新需求、架构组件、跨模块改动、schema/迁移变更、外部集成或契约变更，必须按以下顺序推进。**Phase 1 不可跳过。**

1. **Phase 1：Inquiry & Decision**
   - 用 `/grill-with-docs`（或 `/grilling`）逐问对抗式追问：边界、失败模式、数据归属、迁移、运维成本、验收方式。
   - 先读 `docs/requirements.md`、`CONTEXT.md` 与相关 ADR/spec；事实自己查，决策问用户。
   - 决策落到 ADR 或 spec；词表变化更新 `CONTEXT.md`。
2. **Phase 2：Solution Design** — `/to-spec` 写 spec（跨模块放 `docs/specs/`，模块内放 `<module>/docs/specs/`）。
3. **Phase 3：Implementation** — `/to-tickets` 切 tracer-bullet 票 → `/implement`（配合 `/tdd`）逐票实现。
4. **Phase 4：Verification & Review** — `/code-review` 双轴审查 → `/pre-push-checks` → push/PR。

硬规则：

- Phase 1 完成前，**禁止**调用 `/to-spec`、`/to-tickets`、`/implement`，也不得直接写业务代码、迁移或配置。
- 每个 spec 落地前自检：无 TBD、无自相矛盾、验收可测。
- `/tdd` 在 Phase 3 强制；先确认测试缝，再按垂直切片红→绿推进。
- Phase 4 的 lint/test/typecheck 必须真实跑过才能声称通过。

### 自动进入 Phase 1（默认生效）

检测到**实现级任务**时，第一动作是进入 `/grill-with-docs` / `/grilling`，而不是写代码或落 spec。判定（任一命中）：

- 新功能或业务逻辑开发
- 新架构组件/基础设施（新服务、新数据存储、消息队列等）
- 非平凡的多文件或跨模块改动
- 数据库 schema / 迁移变更
- 新外部集成或契约变更

豁免（可直接做）：

- 纯问答、解释、只读调查，不写代码
- 编辑 `AGENTS.md` / docs / specs / 注释 / typo
- 用户已给出具体复现与范围的 bug fix
- 用户明确说「跳过 grill」「直接做」
- 纯机械配置（重命名、格式化、lint 修复）

默认：不确定时按实现级任务处理，先进入 Phase 1，并向用户说明分类（例如「我判定这是实现级任务 → 进入 Phase 1」），让用户可纠正。

禁止：对实现级任务直接跳到 `/to-spec`、`/to-tickets`、`/implement` 或任何代码/迁移输出。用户要求跳过时，先确认一次豁免，再继续。

斜杠名是跨工具通用写法；在 pi 中显式调用为 `/skill:<name>`（如 `/skill:grilling`、`/skill:pre-push-checks`）。

## Git

- 公开镜像：`main` 为发布分支；贡献者从 `main` 切分支并 PR 回 `main`。公开镜像不含 `dev` 分支与内部工单。
- 维护者私有仓：`dev` 开发，功能完成后 PR 回 `main`。不要直接 push `main`。
- Commit 格式：`{feat,fix,docs,chore,refactor,test}(gateway|server|web|sandbox|docs): <message>`，scope 可省略（跨模块时）。
- 只 stage 本次工作自己改动的文件；禁止 `git add -A` / `git add .`。
- 禁止 `git reset --hard` / `git checkout .` / `git stash` / force push。
- Push 前跑 `/skill:pre-push-checks`（按 outgoing diff 选最小覆盖面）。

## Coding standards

通用：

- 语义与命名跟 `CONTEXT.md` 的领域词汇；改词汇先改表。
- 外部依赖精确锁版本（exact pin），变更依赖必须本地真实 install/compile 过。
- TS 一律 `strict`；不用 `any`（确需时注释理由）。注释与标识符用英文，面向用户的文案与文档用中文。

gateway（Node / TypeScript）：

- ESM（`"type": "module"`）+ NodeNext；本地导入带 `.js` 后缀；`verbatimModuleSyntax`。
- pi 依赖家族（`@earendil-works/*`）必须整族同版本精确锁定，见 `docs/adrs/0001`。
- SSE 断连检测用 `res.on("close")` + `writableEnded` 区分，不要用 `req` 的 close，见 `docs/adrs/0002`。
- 对 pi 的集成只走 SDK / extension 稳定面；experimental 包（pi-server / pi-protocol）不引入。

server（Java 21 / Spring Boot 3）：

- 包结构按层：`controller` / `service`（接口）+ `service/impl`（实现）/ `dao` / `dto` / `config` / `common`；依赖方向 controller → service 接口 → dao，禁止反向与跨层直连。详见 `server/README.md`。
- DTO 用 record 并放在 `dto/<功能>/`；依赖构造器注入；配置走 `@ConfigurationProperties` record。
- 对 gateway 的 HTTP 调用集中在 service 层，controller 只做参数校验与编排。
- 业务错误统一抛 `ApiException`（携带 `ErrorCode`），由 `GlobalExceptionHandler` 输出 `{error:{code,message,retryable}}`；新代码不用 `ResponseStatusException` 拼错误。
- SSE 透传用 `StreamingResponseBody`，客户端断连的 IOException 静默丢弃。

web（Next.js / React）：

- 目录按 `app`（路由/布局）、`components`、`hooks`、`lib`（api/sse/env）、`types` 分层；页面只做组合，HTTP 进 `lib/api`，SSE 解析进 `lib/sse`。详见 `web/README.md`。
- 默认 Server Component；只有需要状态/副作用/事件处理的最小边界标 `"use client"`。
- 函数组件 + hooks；环境变量经 `NEXT_PUBLIC_*` 注入，不硬编码 API 地址。
- 样式：`globals.css` 只放 reset/tokens，组件样式用 CSS Modules。
- 错误统一转 `ApiError`，UI 不展示堆栈或内部地址。

sandbox：

- 隔离参数变更必须同步更新 `sandbox/Dockerfile.python` 注释与 `docs/requirements.md` FR-3。
