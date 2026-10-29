# agent-core

基于 pi 引擎的对话级知识库系统：多轮对话、RAG 知识库、沙箱执行、自动压缩、用户长期记忆。

## Language

### 用户（User）

以邮箱注册的账号主体，全部会话、知识与记忆的归属者；认证后以 userId 出现在 JWT sub 与内部身份 header 中。
_Avoid_: account、tenant、member

### 刷新令牌（Refresh Token）

不透明随机串，经 httpOnly cookie 传输，服务端只存哈希；每次刷新即轮换，检测到旧令牌重用时撤销同族全部令牌。
_Avoid_: session token、persistent token、remember-me

### OIDC 提供方（OIDC Provider）

部署方配置的单一 OIDC issuer，提供授权码登录和已验证邮箱；它不是用户自行添加的模型供应商。
_Avoid_: OAuth server、第三方登录（泛指时）

### 外部身份（External Identity）

OIDC provider 的 `(issuer, subject)` 组合，绑定到一个本地用户；subject 是 provider 的稳定用户标识，不是邮箱。
_Avoid_: OAuth 账号、第三方账号、provider user

### 账号绑定（Identity Link）

把一个外部身份显式关联到已登录的本地用户；不会按邮箱自动发生，同一用户在同一 issuer 下只能有一个外部身份。
_Avoid_: 合并账号、自动关联

### 本地密码（Local Password）

用户在本系统设置的密码凭证；OAuth 创建的账号可以没有本地密码，此时只能用已绑定的外部身份登录。
_Avoid_: OAuth 密码、第三方密码

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

一段持续的多轮对话及其上下文，归属单一用户，由业务层（Java）创建与管理。可处于 active 或 archived（业务归档）状态；业务归档保留历史，可恢复或彻底删除。
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

### Gateway 实例（Gateway Instance）

一个运行中的 gateway 进程，有稳定 ID、可被 Java 访问的地址、会话容量与 draining 状态；多个实例共享同一会话目录。
_Avoid_: 节点、副本、pod

### 会话路由（Session Route）

会话当前归属的 gateway 实例。路由是放置提示；单写者归属由文件锁最终裁决。
_Avoid_: 绑定、分片

### 会话驻留（Resident）

AgentSession 当前在某个 gateway 实例内存中。驻留与会话是否活跃无关；空闲会话可被驱逐。
_Avoid_: 在线、加载中

### 驱逐（Evict）

释放内存中的 AgentSession 与文件锁，保留 JSONL 与 sidecar；下次访问时从磁盘恢复。
_Avoid_: 删除、卸载、回收（口语可用，实体名统一 Evict）

### 排空（Drain）

实例停止接收新会话与新轮次，等待在飞轮次结束或宽限期到期后退出；是滚动更新的安全退出方式。
_Avoid_: 下线、关机、优雅关闭（泛指时）

### 会话归档（Session Archive）

把 90 天未活动的 pi session 文件压缩上传对象存储并删除本地副本；用户下一次发消息时自动恢复，仍可继续对话。与业务状态 archived（用户把会话移出活跃列表）不是同一件事。
_Avoid_: 业务归档、冷备份、删除

### 契约（Contract）

业务层与 Gateway 之间版本化的 HTTP/SSE 协议，定义事件、错误、身份与生命周期。
_Avoid_: API（泛指时）、interface

### 业务层（Business Layer）

Java/Spring Boot 服务：认证、RAG 管线、沙箱调度、画像记忆。不直接调用模型。
_Avoid_: backend（泛指时易混淆）

### Compaction（上下文压缩）

上下文接近模型窗口上限时，把较早消息摘要替换以腾出空间的过程；被压缩的原文保留、可回捞。
_Avoid_: summarization（那是 Compaction 的手段，不是整体机制）

### 上下文占比（Context Usage）

当前会话上下文已占模型窗口的比例，以及触发压缩的阈值；只用于实时展示，不进入模型上下文。
_Avoid_: token 使用率、窗口水位

### 压缩摘要（Compaction Summary）

一次成功 compaction 产出的摘要正文，连同触发原因、压缩前后 token 与时间，投影进 `session_compactions`；累积式覆盖，最新一条即当前生效摘要，仅会话本人可读、admin 不可见。
_Avoid_: compaction 日志、压缩记录（口语可用）、summary 正文

### 已压缩标记（Compacted Marker）

会话头部「已压缩」提示的存在依据：会话存在压缩摘要记录，或本次连接收到过 `compaction` 事件；它不再由上下文占比下降推断。
_Avoid_: 压缩状态、上下文已满

### 消息投影（Message Projection）

业务层从契约事件写入 MySQL 的消息副本，用于展示、历史与统计，不是引擎上下文的来源。
_Avoid_: history store、message store、session store

### 画像事实（Profile Fact）

从会话中提取的、跨会话持久的用户稳定事实或偏好，经筛选后注入后续会话的 system prompt。
_Avoid_: memory、user habit（口语概念，落库与注入的实体是 Profile Fact）

### 记忆开关（Memory Toggle）

用户级设置；关闭后停止画像事实的提取与注入，但保留既有事实，可随时重新开启。
_Avoid_: memory off、disable profile

### 记忆集（Memory Collection）

画像事实的归类容器：系统「通用」「收件箱」或用户创建的场景集合；会话按选中的集合注入事实。
_Avoid_: folder、tag、space

### 切片（Passage）

文档切分后的最小检索单元，带来源文档与序号定位，是引用溯源的粒度。
_Avoid_: chunk、fragment

### 沙箱执行（Sandbox Execution）

在临时容器中隔离执行代码：无网络、资源受限、非 root、只读根文件系统、限时，结束即销毁。
_Avoid_: code runner、REPL

### 沙箱预热池（Sandbox Pool）

提前创建并启动的 idle 沙箱容器集合。每次执行原子取用一个，执行后销毁，不回流、不复用；池只缩短建/启容器的时间。
_Avoid_: 容器复用、执行池（都暗示同一容器跑多次）

### 执行记录（Exec Record）

一次沙箱执行的审计实体，记录代码、触发身份、资源消耗与终态，供追溯与排障；随会话彻底删除而删除，到期后清理。
_Avoid_: exec log、sandbox log、audit row

### 限流（Rate Limit）

按用户或来源 IP 在固定时间窗口内限制请求数与 token 消耗的保护机制；触发后返回 429 与重试窗口。
_Avoid_: 流控、throttle、quota（quota 指长期额度）

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

### OCR 识别（OCR）

扫描版 PDF 没有文本层时的逐页文字识别；由独立 RapidOCR 侧车执行，识别文本进入 Passage，页图不落盘。
_Avoid_: 图片转文字、扫描识别

### 知识库设置（KB Settings）

用户自带的 embedding/rerank 端点与凭证；未配置时回落到部署默认值。生效来源标记为 `user` / `system` / `none`（rerank 另可标记 `disabled`）。
_Avoid_: embedding config、RAG config

### 模型指纹（Model Fingerprint）

文档摄取时记录的 embedding 模型标识与维度，用于在检索前证明向量出处；不一致或缺失时知识库拒绝检索，要求重建。
_Avoid_: model tag、version stamp

### 候选池（Candidate Pool）

检索时从向量库取回、供 rerank 排序的 Passage 集合（默认 10 条）；它决定 rerank 能达到的质量上限。
_Avoid_: shortlist、预选集

### 评测标注（Eval Label）

「问题 → 期望 Passage」的配对，属于某个知识库；用文档内序号与文本指纹锁定目标，文档重建后可能腐化（rotten）。
_Avoid_: golden pair（口语可用）、测试用例

### 检索评测（Retrieval Evaluation）

对一个知识库的全部评测标注执行检索，产出 recall@5、MRR@5、候选池 recall@10 等指标的一次运行；结果按知识库保留历史用于对比。
_Avoid_: RAG eval、质量测试

### 管理角色（Admin）

只读观测角色：可查看全局用量、运营指标与对话轮次 trace，不可查看会话标题、消息正文、代码、密钥或知识库内容。
_Avoid_: 管理员（口语可用，实体名统一 Admin）、root、superuser

### 用量（Usage）

按用户/会话/时间聚合的 token（输入、输出、缓存读、缓存写）、成本估算、执行次数与检索次数；成本是估算值，未知价格显式不可用。
_Avoid_: 账单、计费（billing 是更晚的领域概念）

### 运营看板（Operations Dashboard）

面向部署运维者的只读视图：活跃会话、用量、compaction 频次、检索命中率、沙箱排队时长与 trace 查询。
_Avoid_: 大屏、监控大盘（monitoring 是更底层的基础设施指标）

### 活跃会话（Active Session）

在配置窗口（默认 15 分钟）内有 run 活动的去重会话；不等于 gateway 当前托管的会话数。
_Avoid_: 在线用户、并发会话（并发是 run 级概念）

### 检索命中率（Retrieval Hit Rate）

`kb_search` 调用中返回至少一个片段的占比；它只说明「有没有命中」，不代表相关性质量。
_Avoid_: 检索准确率、召回率（召回率属于检索评测的指标）

### 沙箱排队时长（Sandbox Queue Wait）

从请求执行到获得并发许可之间的等待时间，不含容器创建与代码执行本身。
_Avoid_: 执行耗时、冷启动时间
