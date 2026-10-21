# agent-core 需求文档

版本：v0.1（草案）
日期：2026-02-05
状态：待评审

## 1. 项目定位

基于 [pi](https://github.com/earendil-works/pi-coding-agent)（`@earendil-works/pi-coding-agent`）作为底层 agent 引擎，构建一个对话级知识库系统：

- 多轮对话，流式输出
- 知识库检索（RAG：Retrieval-Augmented Generation，检索增强生成）
- 沙箱化代码执行
- 上下文超限时自动摘要压缩（compaction）
- 跨会话的用户习惯长期记忆

### 1.1 核心架构决策

| 决策 | 结论 | 理由 |
|---|---|---|
| agent 循环自研 or 复用 | 复用 pi（SDK + extension 集成，不 fork 源码） | pi 内置 agent 循环、多 provider 抽象、session 持久化、compaction；自研这些组件的调优成本高 |
| prompt 定制方式 | extension 层 `before_agent_start`（可整体替换 system prompt，`forceSystemPrompt`） | 官方 API 支持，无需 fork；pi 默认 prompt 偏 coding 场景，需替换为通用对话 prompt |
| 知识检索/沙箱 | 以自定义工具形式注册（`kb_search` / `run_code` extension），回调 Java 服务 | pi 的内置工具面向本地编码场景，禁用后仅暴露白名单工具 |
| 部署形态 | Java 主服务 + Node gateway（pi SDK）双服务 | pi 是 TypeScript；通过进程边界隔离，升级 = 换 npm 版本 |

### 1.2 目标

- M1 结束时：单实例可支撑 50 并发活跃会话的完整产品功能
- 沙箱执行零宿主机污染（无文件残留、无网络外联、资源受限）
- 用户记忆对用户可见、可编辑、可删除

### 1.3 非目标（本期不做）

- 实时协同编辑、多人在同一会话内对话
- pi 源码 fork 与 rebrand
- 语音/图像多模态输入
- 自训练模型 / 本地推理

## 2. 总体架构

```
Next.js (SSE) ──HTTPS──> Java (Spring Boot)
                          ├─ 认证 / 多租户 / 业务编排
                          ├─ RAG 摄取与检索 API ──> 向量库 (Qdrant*)
                          ├─ 沙箱执行服务 ──> Docker
                          ├─ MySQL (业务库) / Redis (缓存/限流/锁)
                          └─ HTTP/WS ──> Node Gateway (pi SDK)
                                          ├─ 每活跃会话一个 AgentSession
                                          ├─ ext: kb_search ──回调 Java 检索 API
                                          ├─ ext: run_code ──回调沙箱服务
                                          ├─ ext: prompt 定制（注入用户画像 + 替换 system prompt）
                                          └─ JSONL 事件流 → Java → SSE 转发
```

*向量库选型为开放问题，见 §8。

组件职责：

| 组件 | 职责 | 不负责 |
|---|---|---|
| Next.js | 会话 UI、流式渲染、知识库管理界面、记忆管理界面 | 业务逻辑 |
| Java (Spring Boot 3) | 认证、多租户、RAG 管线、沙箱调度、画像提取与存储、用量计费、事件转发 | agent 循环、LLM 调用 |
| Node Gateway | 托管 pi AgentSession 生命周期、extension 注册、事件流输出、会话冷热管理 | 用户认证（信任 Java 侧传入的会话身份） |
| pi | agent 循环、tool calling、compaction、session 持久化 | 多租户、知识库、沙箱 |

## 3. 功能需求

每条含验收标准（AC）。优先级：P0 = M1 必须，P1 = M2，P2 = 后续。

### FR-1 对话管理（P0）

- 会话 CRUD：创建、列表、删除、重命名；会话归属用户
- 多轮上下文：完整消息历史持久化，刷新/重连不丢失
- 流式输出：token 级增量推送到前端（SSE）
- 中断与继续：用户可中断当前回复（abort）；中断后可继续对话
- 会话分支：pi 原生支持消息树分支，本期仅保留能力不暴露 UI（P2 再暴露）
- 冷启动恢复：gateway 重启或会话换宿主后，可从存储恢复上下文继续对话

AC：
1. 断网重连后消息历史完整，无重复、无丢失
2. 中断后 200ms 内前端停止接收增量，状态回到可输入
3. kill -9 gateway 进程后重启，活跃会话可恢复继续对话

### FR-2 知识库 / RAG（P0）

- 文档摄取管线：上传 → 格式解析 → 切分 → embedding → 向量库；元数据与原文入 MySQL
- 支持格式（首期）：PDF、Markdown、TXT、docx、HTML URL 抓取
- 检索工具 `kb_search`：模型在对话中按需调用；Java 侧执行向量检索 + 重排（rerank），返回 top-k 片段
- 知识库隔离：按用户/工作区隔离，检索只命中授权范围
- 引用溯源：回答中被引用的知识片段可展示来源文档与定位
- 增量更新：文档重新上传后旧切片失效并重建

AC：
1. 上传 100 页 PDF 后 5 分钟内可被检索命中
2. 回答引用知识库内容时，前端可展示「来自文档 X 第 Y 段」
3. 用户 A 的检索请求不返回用户 B 的文档切片

### FR-3 沙箱代码执行（P0）

- 工具 `run_code`：模型在对话中提交代码，沙箱执行后回传结果
- 首期语言：Python；执行环境镜像版本固定
- 隔离要求（每词含义见下）：
  - 临时容器：一次执行一个容器，结束即销毁
  - 资源限制：CPU 配额、内存上限（默认 512MB）、磁盘 tmpfs 只写工作目录
  - 网络禁用：容器无网络能力
  - 非 root 运行
  - 超时：默认 30s，超时杀死并回传超时说明
- 结果回传：stdout/stderr/退出码；单次回传大小上限（如 64KB，超出截断标注）
- 审计：每次执行的代码、触发会话、资源消耗、结果状态入 MySQL `exec_records`
- 排队与并发上限：per-user 并发执行数限制（Redis 信号量），超限排队

术语：tmpfs = 内存文件系统，容器内写入不落宿主机磁盘。

AC：
1. 执行 `requests.get(...)` 网络请求失败，错误信息正常回传模型
2. 死循环代码在超时阈值后被杀死，不拖垮宿主机
3. 审计表中可查到每次执行的完整代码与资源消耗
4. 同一用户并发执行超过上限时，后续请求进入排队而非报错

### FR-4 自动总结记忆 / compaction（P0）

- 复用 pi 内置 compaction：上下文接近模型窗口上限时，自动将较早消息摘要压缩
- 策略可配：触发阈值（token 占比）、保留最近 N 轮原文
- 不失忆保障：摘要替换的原文仍持久化（pi session 存储 + MySQL 投影），可按需回捞
- 摘要可见：用户可查看当前会话的压缩摘要（P1）

AC：
1. 单会话连续对话至 3 倍模型窗口长度，仍能正确引用第 1 轮明确过的关键事实
2. compaction 触发时对话不中断，用户无感知（除可选的提示条）

### FR-5 用户习惯长期记忆（P0 提取与注入，P1 管理界面）

- 事实提取：会话空闲或结束后，异步 LLM 任务从对话中提取稳定事实与偏好（如「用户偏好中文回复」「用户的项目用 MySQL 8」），写入 MySQL `profile_facts`
- 冲突消解：新事实与旧事实矛盾时，以版本化更新（保留历史，标记被取代），不静默覆盖
- 注入：下一会话开始时，该用户的事实经筛选后注入 system prompt（经 extension 的 `before_agent_start`）
- 用户可控：用户可查看、编辑、删除自己的全部记忆条目；可一键关闭记忆功能（合规要求）
- 提取范围控制：敏感个人信息（凭证、密码类）不入库——提取 prompt 明确排除 + 落库前规则过滤

AC：
1. 用户在会话 A 中说明偏好后，新开会话 B 首轮回复即体现该偏好
2. 用户删除某条记忆后，后续会话不再体现
3. 对话中出现密码字样的内容不出现在 `profile_facts`

### FR-6 多租户与安全（P0）

- 认证：JWT（access + refresh）；首期邮箱密码注册，P1 接 OAuth
- 会话隔离：所有查询按 user_id 过滤；gateway 会话与 user_id 强绑定，由 Java 侧传递与校验
- pi 内置工具白名单：仅启用 `read` 等只读工具（或全部禁用），`bash`/`edit`/`write` 禁用；通过 pi 工具选择机制配置
- API 限流：per-user RPM/TPM（Redis）

AC：
1. 无有效 JWT 的请求全部 401
2. 模型在对话中尝试调用未授权工具时，该调用被拒绝且模型收到明确错误
3. 单用户超限后收到 429 与重试窗口

### FR-7 可观测与运营（P1）

- 用量统计：token 用量、执行次数、按用户/会话聚合；成本估算
- 关键链路 trace：请求 → gateway → 模型/工具 → 响应，全链路 request_id 贯穿
- 运营指标看板：活跃会话数、compaction 频次、检索命中率、沙箱排队时长

## 4. 非功能需求

| 项 | 要求 |
|---|---|
| 并发 | 单 gateway 进程 ≥50 活跃会话（PoC 实测校准）；gateway 可横向扩展，会话按 hash 亲和路由 |
| 延迟 | 首 token P95 < 3s（扣除模型自身 TTFB）；`kb_search`/`run_code` 回传 P95 < 500ms（不含代码执行本身） |
| 可用性 | Java 与 gateway 任一重启不丢已持久化消息；部署滚动更新不断流 |
| 数据安全 | MySQL 加密存储凭证类字段；传输全程 TLS；沙箱执行产物保留 7 天后清理 |
| 版本策略 | pi 锁定精确版本；升级前跑契约测试（RPC 事件 schema、extension API）；不依赖 experimental 包（pi-server/pi-protocol） |

## 5. 技术选型

| 层 | 选型 | 说明 |
|---|---|---|
| 前端 | Next.js 14+，SSE | 流式渲染；知识库与记忆管理界面 |
| 业务层 | Java 21 + Spring Boot 3 | 认证、编排、RAG 管线、沙箱调度；HTTP 客户端调用 gateway，无需 Spring AI 的 agent 能力 |
| agent 引擎 | Node 20+ 服务，`@earendil-works/pi-coding-agent` SDK | gateway；extension 三件套：kb_search、run_code、prompt 定制 |
| 持久化 | MySQL 8 | 用户、会话、消息投影、画像、文档元数据、执行审计、用量 |
| 缓存 | Redis 7 | 活跃会话路由表、限流、沙箱并发信号量、分布式锁 |
| 向量库 | 待定（Qdrant / Milvus / pgvector 三选一，M0 定） | 知识切片的向量检索 |
| 沙箱 | Docker Engine（docker-java 调用） | 首期 runc；gVisor (runsc) 作为 P2 加固选项 |
| embedding / rerank | 待定（跟随主模型 provider 或独立服务） | M0 与向量库一并定 |

## 6. 数据模型草案（MySQL）

仅列关键表与字段要点，详细 schema 在 M0 设计评审定稿。

| 表 | 要点 |
|---|---|
| `users` | id, email, password_hash, settings(json: 记忆开关等), created_at |
| `sessions` | id, user_id, title, status(active/archived), pi_session_id, last_active_at |
| `messages` | id, session_id, role, content, tokens_in/out, pi_entry_ref, created_at（由 gateway 事件流投影写入） |
| `profile_facts` | id, user_id, fact_text, category, status(active/superseded/deleted), supersedes_id, source_session_id, created_at |
| `documents` | id, user_id, title, mime, raw_path, status(parsing/indexed/failed), checksum |
| `doc_chunks` | id, document_id, seq, content, vector_id, token_count |
| `exec_records` | id, session_id, user_id, language, code, exit_code, stdout/stderr_ref, duration_ms, cpu/mem_used, status |
| `api_usage` | id, user_id, session_id, model, tokens_in/out, cost_est, created_at |

Redis key 草案：

| key | 用途 | TTL |
|---|---|---|
| `route:sess:{session_id}` | 会话→gateway 实例的路由表 | 会话活跃期 |
| `rl:{user_id}:{window}` | 限流计数 | 窗口期 |
| `sem:sandbox:{user_id}` | 沙箱并发信号量 | 执行期间 |

向量库集合：`kb_chunks {content, embedding, doc_id, user_id, seq}`，按 `user_id` 过滤检索。

## 7. 里程碑

| 阶段 | 周期 | 交付 |
|---|---|---|
| M0 PoC | 1–2 周 | 端到端 demo：单 gateway 进程 + prompt 整体替换（验证 forceSystemPrompt 定制程度）+ kb_search / run_code extension 打通 + SSE 链路；冻结 Java↔gateway 事件契约 v1；定向量库与 embedding 选型 |
| M1 MVP | 4–6 周 | 全部 P0：多租户认证、RAG 摄取管线、沙箱隔离与审计、compaction 配置化、画像提取/注入/用户可控、MySQL 消息投影 |
| M2 硬化 | 3–4 周 | gateway 多实例与亲和路由、冷启动恢复压测、可观测看板、限流调优、gVisor 评估 |

## 8. 风险与开放问题

| # | 风险/问题 | 缓解 |
|---|---|---|
| R1 | pi 上游迭代快，API 变更 | 锁版本 + 升级契约测试；只用 SDK/extension 稳定面，不用 experimental 包 |
| R2 | 沙箱逃逸（容器非绝对边界） | 禁网 + 非 root + 资源上限 + 只读 rootfs；P2 评估 gVisor；镜像最小化 |
| R3 | compaction 摘要丢细节导致「失忆」 | 原文不删（pi session + MySQL 投影双保）；摘要 prompt 迭代；M1 加「引用第 1 轮事实」回归用例 |
| R4 | gateway 单进程内存上限未知 | M0 实测单进程可托管会话数，校准 §4 并发目标 |
| R5 | 向量库/embedding 选型未定 | M0 用 100 页 PDF 走通摄取→检索→引用闭环后定稿 |
| O1 | Java↔gateway 契约（事件流 schema、会话生命周期指令）需 M0 冻结 v1 | — |
| O2 | 用户画像注入的 token 预算（事实条数上限与筛选策略） | M1 实测定 |
| O3 | 多语言沙箱扩展顺序（Python 之后） | M2 定 |
| O4 | 浏览器渲染 / agent 工作区能力是否引入 AIO Sandbox（agent-infra/sandbox，Apache-2.0）作为独立 workspace 层 | M2 后评估；不作为 run_code 底座（ADR-0003） |
