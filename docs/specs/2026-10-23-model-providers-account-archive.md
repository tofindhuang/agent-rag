# 模型供应商接入 + 账号安全 + 归档闭环

日期：2026-10-23
状态：Phase 1 grilling 已完成（Q1–Q19）
关联需求：FR-1（会话 CRUD/历史）、FR-6（认证/安全）
关联 ADR：[0007](../adrs/0007-jwt-access-rotating-refresh-auth.md)、[0008](../adrs/0008-gateway-cold-start-local-session-files.md)、[0010](../adrs/0010-user-supplied-model-providers.md)、[0011](../adrs/0011-user-initiated-session-purge.md)、[0005](../adrs/0005-qdrant-external-embedding.md)

## Problem Statement

当前产品有三个断点：

1. 用户只能使用部署者预置的模型，无法自带供应商（OpenAI-compatible / Anthropic Messages）；界面上也看不到会话实际使用的模型。
2. 账号只能登录/登出，不能改密码、不能让其他设备下线。
3. 「归档」是只写不读的操作：归档后会话从列表消失，但没有任何入口查看、恢复或彻底删除，用户无法理解数据去了哪里。

## Solution

- 用户可在设置页添加自己的模型供应商，探测并启用模型，设为默认；新建会话时选择模型（默认用户默认模型，否则系统默认），会话头部显示实际模型。
- 账号安全：改密码 / 登出所有设备，均撤销全部 refresh token。
- 归档中心：侧边栏切换「进行中 / 已归档」；已归档可恢复或彻底删除（连同 gateway 的 pi 文件）。
- 部署开关 `ALLOW_SYSTEM_DEFAULT_MODEL` 控制是否允许无供应商用户使用系统默认模型。

## User Stories

1. As a 用户, I want 添加一个 OpenAI-compatible 供应商（名称、Base URL、API Key）, so that 我能用自己的模型额度
2. As a 用户, I want 添加一个 Anthropic Messages 供应商, so that 我能接入 anthropic 风格端点（如 LiteLLM）
3. As a 用户, I want 保存前探测供应商的模型列表, so that 坏 key/错 URL 能立即暴露
4. As a 用户, I want 从探测结果中勾选要启用的模型, so that 选择器不被几十个模型塞满
5. As a 用户, I want 探测失败时手动填写模型 ID, so that 不支持 /models 的供应商也能用
6. As a 用户, I want 为模型补充上下文窗口/reasoning/vision 元数据, so that compaction 与 thinking 行为正确
7. As a 用户, I want 设置一个默认模型, so that 新建会话不用每次重选
8. As a 用户, I want 新建会话时选择模型（默认我的默认模型）, so that 不同任务用不同模型
9. As a 用户, I want 在会话头部看到实际使用的模型, so that 我知道当前回复由哪个模型产生
10. As a 用户, I want 没有配置供应商时（且系统默认开启）直接可聊, so that 注册后不被配置流程卡住
11. As a 运维, I want 关闭系统默认模型, so that 生产环境强制用户自带 key
12. As a 用户, I want 删除供应商, so that 失效凭证不再留在系统里
13. As a 用户, I want 模型故障时把会话切到可用模型, so that 历史会话不会永久不可用
14. As a 用户, I want 修改密码, so that 泄露的旧密码失效
15. As a 用户, I want 登出所有设备, so that 丢失的设备不能继续使用我的账号
16. As a 用户, I want 查看已归档会话, so that 我知道归档的数据还在
17. As a 用户, I want 恢复已归档会话, so that 我能继续之前的对话
18. As a 用户, I want 彻底删除已归档会话, so that 我的数据真正被移除（含引擎上下文）
19. As a 用户, I want 彻底删除前看到二次确认, so that 不会误删
20. As a 用户, I want 归档列表支持分页, so that 会话很多时也能翻到底
21. As a 开发者, I want API Key 加密落库且永不回显, so that 数据库或接口泄露不暴露明文凭证

## Implementation Decisions

### 数据模型（MySQL，Flyway up-only）

- `model_providers`：id BIGINT 自增；user_id FK；name；api ENUM(`openai-completions`,`anthropic-messages`)；base_url；api_key_cipher（AES-GCM，IV 与密文一起 base64）；api_key_hint（末 4 位，仅展示）；created_at/updated_at。
- `provider_models`：id BIGINT 自增；provider_id FK ON DELETE CASCADE；model_id；display_name；context_window；supports_reasoning；supports_vision；created_at；UNIQUE(provider_id, model_id)。只存用户启用/手填的模型。
- `user_model_defaults`：user_id 主键、model_id FK provider_models ON DELETE CASCADE；独立表避免 MySQL 不支持 `ADD COLUMN IF NOT EXISTS` 的非幂等迁移，也避免与 provider 删除形成环。
- `session_models`：session_id 主键、provider_model_id（FK ON DELETE SET NULL）、model_label（如 `SiliconFlow / deepseek-chat`，系统默认时为实际模型名）、model_source ENUM(`system`,`user`)。独立表避免非幂等 ALTER，并随会话级联删除。
- 会话模型在创建/恢复时由 Java 解析并写入；历史会话在供应商删除后仍保留 label。

### 凭据与网关（ADR-0010）

- API Key 使用 AES-GCM 加密，密钥来自 `MODEL_SECRET_KEY`（32 字节 base64，启动校验）；web 只写不读，读取接口只返回掩码 `••••1234`。
- Java 在 `PUT /sessions/:id` 时把该会话选定模型的配置（api/baseUrl/apiKey/modelId/元数据）下发给 gateway；gateway 用 per-session `ModelRuntime` 构建 `AgentSession`，内存持有、不落盘；重启后下一次 prompt 前 ensure 重新下发。
- 探测：Java 代理 gateway 的 `POST /providers/probe`，由 gateway 发起 `/models` 请求（不使用 pi 默认凭证）。
- SSRF：默认只允许公网 HTTPS；`MODEL_ALLOW_PRIVATE_ENDPOINTS=true` 放行私网/HTTP；云元数据地址（169.254.169.254 及 IPv6 等价）永远拒绝；请求前做 DNS 解析并检查所有解析结果。DNS rebinding 为残余风险，记录在 ADR。
- 系统默认模型：`ALLOW_SYSTEM_DEFAULT_MODEL`（默认 true）。关闭时，无用户模型的会话创建返回 `MODEL_NOT_CONFIGURED`，UI 引导去设置。

### Java API（认证后）

- `GET /api/models`：启用的模型列表 + 用户默认 + 系统默认可用性与 label。
- `POST /api/models/probe`：`{api, baseUrl, apiKey, providerId?}`；providerId 存在且未传 key 时使用已存 key。
- `GET /api/models/providers` / `POST` / `PATCH /{id}` / `DELETE /{id}`：供应商与启用模型 CRUD；编辑 key 为整体替换；删除供应商级联删除其模型并把引用它的 `users.default_model_id` 置空。
- `PUT /api/models/default`：`{modelId|null}`，null 表示使用系统默认。
- `POST /api/chat/sessions/{id}/model`：故障恢复用，切换会话模型（下一轮生效）；服务端允许，UI 仅在 `MODEL_UNAVAILABLE` 后暴露。
- 创建会话 `POST /api/chat/sessions` 增加可选 `title`、`modelId`；`useSystemDefault=true` 显式使用系统默认（与 `modelId` 互斥，用于绕过用户默认模型）。

### 账号安全

- `POST /api/auth/change-password`：验证当前密码、新密码 8–72 字节、更新 BCrypt、撤销该用户全部 refresh token（含当前）。
- `POST /api/auth/logout-all`：撤销全部 refresh token。
- 两者成功后前端清 token 并回登录页；access token 仍按既有 15 分钟窗口自然过期（不引入 denylist）。

### 归档闭环

- `GET /api/chat/sessions?status=active|archived`（默认 active）沿用复合游标；归档列表按 `last_active_at DESC, id DESC`。
- `POST /api/chat/sessions/{id}/restore`：status=active、deleted_at=NULL、last_active_at=now。
- `DELETE /api/chat/sessions/{id}/permanent`：仅允许 archived；先调用 gateway `DELETE /sessions/:id/files`（幂等、校验归属），成功后再事务删除 messages → runs → sessions；文件删除失败返回 502 可重试，业务行保留（ADR-0011）。
- 归档时仍只释放 gateway 内存会话，不删文件；彻底删除才删。

### 契约 v1 加法扩展

- `PUT /sessions/:id` 可选 body `{model:{providerId, name, api, baseUrl, apiKey, modelId, displayName, contextWindow, reasoning, vision}}`；响应增加 `model:{provider,id,name}`。
- `POST /providers/probe`：共享 token 认证；返回 `{models:[{id,name}]}`；错误走契约 envelope。
- `DELETE /sessions/:id/files`：共享 token + `X-User-Id`；释放内存会话并删除 JSONL + sidecar；幂等 204。
- 主版本不变（纯加法）；更新 `contract/v1/README.md`。

### UI（功能切片，基础样式；视觉重做在切片 2）

- `/settings`：模型（供应商列表/添加/编辑/删除、探测与模型勾选、高级元数据、默认模型）、账号（改密码、登出所有设备）。
- 新建会话弹窗：模型下拉 + 可选标题。
- 头部：模型标签（`系统默认 · <model>` 或 `<供应商> / <模型>`）、设置入口、退出登录。
- 侧边栏：进行中/已归档切换；归档项提供恢复与彻底删除（确认框）；归档列表支持「加载更多」。
- 错误：`MODEL_NOT_CONFIGURED` 引导到设置；`MODEL_UNAVAILABLE` 提供切换模型对话框。

### 错误码（新增）

`MODEL_NOT_CONFIGURED`（409）、`MODEL_UNAVAILABLE`（409/502）、`PROVIDER_UNREACHABLE`（502）、`PROVIDER_AUTH_FAILED`（502）、`PROVIDER_PROBE_FAILED`（502）、`MODEL_SWITCH_NOT_ALLOWED`（409，保留给后续策略）、`SESSION_PURGE_FAILED`（502，可重试；gateway 文件删除失败时业务行保留）。

## Testing Decisions

- 好测试仍只经 HTTP / 网关 HTTP 面与真实 MySQL/Redis（Testcontainers），不探测 DAO 内部；密钥加密额外用数据库断言验证「库中无明文」。
- server：供应商 CRUD + 归属隔离、加密落库、probe 代理成功/失败映射、默认模型、系统默认开关两种状态、建会话模型快照、故障切换、改密码/登出所有后旧 refresh 全部失效、归档列表/恢复/彻底删除（fake gateway 记录文件删除调用、顺序与重试）、彻底删除只允许 archived。
- gateway：probe 的 SSRF 策略（公网/私网/HTTP/元数据/DNS 多结果）、provider 配置翻译与 per-session runtime、PUT model 回显、`DELETE /sessions/:id/files` 幂等与归属校验、文件确实删除。
- web：typecheck + build；浏览器手工验收补充：添加供应商（本地 stub 端点）→ 选择模型 → 建会话 → 对话 → 模型标签、归档/恢复/彻底删除、改密码后重登。
- 现有 79 server / 28 gateway 测试保持全绿。

## Out of Scope

- 会话内正常换模型（仅故障恢复）、每轮/每条消息记录模型
- 删除账号、邮箱修改、头像、OAuth/邮箱验证
- 模型共享、管理员模型目录、计费/配额、用量看板
- probe/聊天限流、审计看板（残余风险）
- DNS rebinding 的完全防护（记录为残余风险）
- 视觉重做、浅深主题（切片 2）

## Further Notes

- 新环境变量：`MODEL_SECRET_KEY`、`MODEL_ALLOW_PRIVATE_ENDPOINTS`、`ALLOW_SYSTEM_DEFAULT_MODEL`。
- 轮换 `MODEL_SECRET_KEY` 会使已存 API Key 无法解密，需要用户重新填写；运维需先迁移或接受重录。
- 系统默认模型来自 gateway 现有 pi 配置；关闭开关后，无用户模型的会话在创建时即失败，不进入 gateway。
- 逐轮模型记录留到「按消息追溯模型/成本」需求出现时再做。
