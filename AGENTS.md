# AGENTS.md

This file provides guidance to AI coding agents working in this repository.

## Project Overview

agent-core 是基于 [pi](https://github.com/earendil-works/pi-coding-agent) 为底层 agent 引擎的对话级知识库系统。需求与架构见 [docs/requirements.md](docs/requirements.md)（唯一权威来源）。

职责边界（一句话版）：**Java 永远不直接调用 LLM；gateway 是唯一的模型通路；pi 不做业务。**

## Module Map

| 模块 | 栈 | 职责 |
|---|---|---|
| `gateway/` | Node 20+ / TypeScript / pi SDK | 托管 AgentSession，SSE 事件流透传，kb_search / run_code 工具 |
| `server/` | Java 21 / Spring Boot 3 | 认证、RAG 管线、沙箱调度、画像记忆、业务编排 |
| `web/` | Next.js / React | 聊天 UI、知识库与记忆管理界面 |
| `sandbox/` | Docker 镜像 | 隔离代码执行（无网络、资源受限、非 root、只读根） |
| `docs/` | — | 需求文档、specs、tickets、ADRs |

### Per-module commands

```bash
# gateway / web
cd gateway && npm run typecheck     # web 同名
cd gateway && npm run dev           # :3001；web 为 :3000

# server（需 JDK 21）
cd server && mvn -q compile
cd server && mvn spring-boot:run    # :8080

# infra
docker compose up -d                # mysql + redis
```

模型凭证：gateway 复用本机 pi 凭证存储（`~/.pi/agent/`），仓库与 `.env` 中不存任何密钥。

## Documentation conventions

| 内容 | 位置 | 由哪个 skill 产出 |
|---|---|---|
| 需求 | `docs/requirements.md` | — |
| Specs（跨模块） | `docs/specs/YYYY-MM-DD-<slug>.md` | `/to-spec` |
| Specs（模块内） | `<module>/docs/specs/` | `/to-spec` |
| Tickets | `docs/tickets/<slug>/NN-<slug>.md` | `/to-tickets` |
| 决策地图 | `docs/tickets/<slug>/MAP.md` | `/wayfinder` |
| ADRs | `docs/adrs/`（跨模块）、`<module>/docs/adrs/`（模块内） | `/domain-modeling` |
| 领域词汇表 | `CONTEXT.md` | `/domain-modeling` |

同一套 skills 存放两份：`.pi/skills/`（pi 自动发现）与 `.agents/skills/`（其他 agent 工具的通用位置）。**改动任一份后必须同步另一份**（`cp -R .pi/skills/. .agents/skills/` 或反向），保持两处一致。

规范流程：讨论定型 → `/to-spec` 写 spec → `/to-tickets` 切 tracer-bullet 票 → `/implement`（配合 `/tdd`）逐票实现 → `/code-review` 审查 → `/pre-push-checks` 后 push。斜杠名是跨工具通用写法；在 pi 中显式调用为 `/skill:<name>`（如 `/skill:pre-push-checks`）。

## Git

- 分支：`dev` 开发，功能完成后 PR 回 `main`。不要直接 push `main`。
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
