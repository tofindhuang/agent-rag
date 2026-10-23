# agent-core

基于 pi 引擎的对话级知识库系统：多轮对话、RAG 知识库、沙箱执行、自动压缩、用户长期记忆。

## Language

### 用户（User）

以邮箱注册的账号主体，全部会话、知识与记忆的归属者；认证后以 userId 出现在 JWT sub 与内部身份 header 中。
_Avoid_: account、tenant、member

### 刷新令牌（Refresh Token）

不透明随机串，经 httpOnly cookie 传输，服务端只存哈希；每次刷新即轮换，检测到旧令牌重用时撤销同族全部令牌。
_Avoid_: session token、persistent token、remember-me

### 会话（Session）

一段持续的多轮对话及其上下文，归属单一用户，由业务层（Java）创建与管理。
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
