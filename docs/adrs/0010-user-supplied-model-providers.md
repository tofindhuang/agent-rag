# 用户自带模型供应商：Java 加密存储 + 会话级下发，gateway 仅内存持有

Date: 2026-10-23

## Status

Accepted

## Context

用户要求自行添加模型供应商（OpenAI-compatible / Anthropic Messages）并在会话中选择模型。这带来三个新问题：

- API Key 是长期凭证，存哪里、谁能读、如何避免回显；
- gateway 是唯一生成式模型通路，但用户配置属于业务数据；
- 用户可填任意 Base URL，gateway 会向它发请求，形成 SSRF 面。

pi 的 `ModelRuntime` 支持运行时注册 provider 与运行时 API Key，`createAgentSession` 可注入 `modelRuntime` 与 `model`，因此按会话构建隔离的模型运行时可行。

## Decision

- **持久化归 Java**：`model_providers` 存 AES-GCM 加密后的 API Key（密钥 `MODEL_SECRET_KEY`），读取接口只返回掩码；web 只写不读。
- **按会话下发**：Java 在 `PUT /sessions/:id` 时附带该会话模型的完整配置（含解密后的 key），gateway 用 per-session `ModelRuntime` 构建 `AgentSession`，仅内存持有、不落盘；重启后由下一次 ensure 重新下发。
- **探测走 gateway**：模型列表探测由 gateway 发起（Java 仅代理请求），不把生成式模型凭证带进 Java 的直连调用。
- **协议范围**：`openai-completions` 与 `anthropic-messages` 两种；数据模型保留 `api` 字段，后续可加。
- **出站安全**：默认仅公网 HTTPS；`MODEL_ALLOW_PRIVATE_ENDPOINTS=true` 时允许私网/HTTP；云元数据地址永远拒绝；请求前解析 DNS 并检查全部结果。DNS rebinding 未完全消除，记录为残余风险。
- **系统默认模型**：`ALLOW_SYSTEM_DEFAULT_MODEL` 开关（默认开）。关闭时无用户模型的会话创建失败（`MODEL_NOT_CONFIGURED`），不进入 gateway。

## Considered Options(被否的备选)

- **gateway 自己加密存盘（文件/SQLite）** — gateway 开始持久化用户业务数据，多实例、备份、审计边界都变差。
- **Java 直接调用供应商 `/models` 或 embedding 式直连** — 破坏「Java 不碰模型凭证/模型通路」的边界。
- **只允许管理员预配置模型** — 不满足用户自带供应商的目标。
- **Key 只存会话内存** — 刷新/重启即失，不可用。
- **允许任意 Base URL** — SSRF 直接暴露给所有注册用户。
- **只允许公网 HTTPS，不提供开关** — 自建 LiteLLM / 局域网端点不可用。
- **为每个用户启动独立 gateway 进程** — 隔离强但运维成本与资源占用不成比例，M1 不需要。

## Consequences

- gateway 在会话生命周期内持有明文 key（内存），进程转储/调试需按敏感数据处理。
- 轮换 `MODEL_SECRET_KEY` 会让已存 key 无法解密，需要重新录入或先做迁移脚本。
- DNS rebinding 仍可能在解析校验与实际连接之间发生，属于已知残余风险；更高安全等级部署应在网络层限制 gateway 出站。
- 多实例 gateway 时，模型配置随 ensure 下发，不依赖共享文件；与 ADR-0008 的会话文件共享问题解耦。
- probe 端点可能被滥用做公网探测，M1 先依赖注册门槛与 SSRF 策略，限流留后续切片。
