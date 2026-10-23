# agent-core

基于 pi 引擎的对话级知识库系统：多轮对话、RAG 知识库、沙箱执行、自动压缩、用户长期记忆。

## Language

### 用户（User）

以邮箱注册的账号主体，全部会话、知识与记忆的归属者；认证后以 userId 出现在 JWT sub 与内部身份 header 中。
_Avoid_: account、tenant、member

### 刷新令牌（Refresh Token）

不透明随机串，经 httpOnly cookie 传输，服务端只存哈希；每次刷新即轮换，检测到旧令牌重用时撤销同族全部令牌。
_Avoid_: session token、persistent token、remember-me

### 模型供应商（Model Provider）

用户自行添加的外部模型服务配置（协议、Base URL、加密的 API Key），仅对添加者可见，是会话选择模型的来源。
_Avoid_: channel、vendor、endpoint（泛指时）

### 供应商模型（Provider Model）

从某个模型供应商启用的具体模型及其能力元数据，是新建会话时的可选项。
_Avoid_: model registry、catalog（后者指全局目录，本项目没有）

### 系统默认模型（System Default Model）

部署者通过 gateway 的 pi 配置提供的兜底模型；`ALLOW_SYSTEM_DEFAULT_MODEL=false` 时禁用，用户必须自带供应商。
_Avoid_: global model、platform model

### 会话（Session）

一段持续的多轮对话及其上下文，归属单一用户，由业务层（Java）创建与管理。可处于 active 或 archived（归档）状态；归档保留历史，可恢复或彻底删除。
_Avoid_: conversation、chat、对话（作名词指 Session 时统一用「会话」）

### AgentSession

gateway 进程内由 pi 托管的引擎侧会话实例，与业务侧 Session 一一对应，是唯一的模型上下文载体。
_Avoid_: pi session、engine session

### 轮次（Turn）

用户一次发送所对应的业务单位，由 turnId 标识，是消息幂等与重试的边界；同一轮次的重试会产生新的 Run。
_Avoid_: message、request

### 执行（Run）

一次 prompt 触发的引擎侧完整执行，由 runId 标识，包含一次或多次模型调用与工具调用，以唯一终态结束。
_Avoid_: job、task、turn

### Gateway（网关）

托管 AgentSession 的 Node 服务，系统中唯一与 LLM 通信的组件。
_Avoid_: engine、pi server

### 契约（Contract）

业务层与 Gateway 之间版本化的 HTTP/SSE 协议，定义事件、错误、身份与生命周期。
_Avoid_: API（泛指时）、interface

### 业务层（Business Layer）

Java/Spring Boot 服务：认证、RAG 管线、沙箱调度、画像记忆。不直接调用模型。
_Avoid_: backend（泛指时易混淆）

### Compaction（上下文压缩）

上下文接近模型窗口上限时，把较早消息摘要替换以腾出空间的过程；被压缩的原文保留、可回捞。
_Avoid_: summarization（那是 Compaction 的手段，不是整体机制）

### 消息投影（Message Projection）

业务层从契约事件写入 MySQL 的消息副本，用于展示、历史与统计，不是引擎上下文的来源。
_Avoid_: history store、message store、session store

### 画像事实（Profile Fact）

从会话中提取的、跨会话持久的用户稳定事实或偏好，经筛选后注入后续会话的 system prompt。
_Avoid_: memory、user habit（口语概念，落库与注入的实体是 Profile Fact）

### 切片（Passage）

文档切分后的最小检索单元，带来源文档与序号定位，是引用溯源的粒度。
_Avoid_: chunk、fragment

### 沙箱执行（Sandbox Execution）

在临时容器中隔离执行代码：无网络、资源受限、非 root、只读根文件系统、限时，结束即销毁。
_Avoid_: code runner、REPL

### 工具（Tool）

模型可主动调用的操作。首期两个：`kb_search`（知识库检索）、`run_code`（沙箱执行）。
_Avoid_: function、plugin

### 知识库文档（KB Document）

用户上传或通过 URL 摄取的文件实体；元数据在 MySQL，原文在对象存储，切片与向量由摄取管线生成。
_Avoid_: file、attachment、resource

### 切片（Passage）

文档切分后的最小检索与引用单位，带文档内序号与可选页码；embedding 与引用都以它为粒度。
_Avoid_: chunk（口语可用，实体名统一 Passage）、segment

### 摄取（Ingestion）

把文档原文转成可检索切片的过程：解析、切分、embedding、写入向量库；异步执行，可重试与重建。
_Avoid_: import、indexing（重建索引是 Ingestion 的一种）

### 引用（Citation）

回答所依据的具体 Passage 及其文档标题、段序号与页码的展示。
_Avoid_: source、reference（口语可用，实体是 Citation）

### 知识库设置（KB Settings）

用户自带的 embedding/rerank 端点与凭证；未配置时回落到部署默认值。
_Avoid_: embedding config、RAG config
