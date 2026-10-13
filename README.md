# agent-core

基于 [pi](https://github.com/earendil-works/pi-coding-agent) 为底层 agent 引擎的对话级知识库系统。

核心能力：多轮对话（流式）、知识库检索（RAG）、沙箱代码执行、上下文自动压缩（compaction）、用户习惯长期记忆。

- 需求文档：[docs/requirements.md](docs/requirements.md)
- 架构：Next.js（前端）+ Java/Spring Boot（业务层）+ Node Gateway（pi SDK 引擎层）+ MySQL / Redis / 向量库 / Docker 沙箱
