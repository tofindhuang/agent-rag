# OCR 采用独立 RapidOCR 侧车，Java 只做 PDF 渲染与编排

Date: 2026-10-24

## Status

Accepted（扩展 `docs/specs/2026-10-23-knowledge-base-rag.md` 原 Out of Scope 中的 OCR）

## Context

知识库首版只处理有文本层的 PDF；扫描件/图片型 PDF 会被 `KbTextChunker` 以 `DOCUMENT_EMPTY` 永久失败。用户要求引入 OCR，让扫描版中文 PDF 也能被检索与引用。

可选实现有三类：

- 在 Java 进程内嵌 Tesseract（JNA/Tess4J 或 CLI）：部署最轻，但中文准确率一般，native 依赖跨平台、进程内资源不可控；
- 独立 OCR 侧车：新增一个常驻服务，Java 通过内部 HTTP 调用；部署面变大，但引擎、模型、并发与资源可以独立演进；
- 云端 OCR：准确率高，但引入外部凭证与数据出境，违背自托管目标。

约束：

- 服务端是 Java，不直接调用 LLM；OCR 不是生成式模型，但仍应保持「Java 只编排、重活交给专用进程」的边界；
- 扫描件页图是用户原文的派生数据，不应落盘或进入对象存储；
- 首版只做 PDF 逐页兜底，不做 DOCX/HTML 内嵌图片、手写体、版面/表格重建；
- 文本型 PDF 的 5 分钟验收不能被 OCR 回归。

## Decision

- 新增独立模块 `ocr/`：Python 3.12 + FastAPI + **RapidOCR（ONNX Runtime）**，无 PaddlePaddle 重依赖；模型烤进镜像，CPU 推理。
- 契约：`POST /ocr` 收 **单页 PNG 原始字节**（`Content-Type: image/png`），返回 `{ text, confidence, lines[] }`；`GET /health` 返回引擎与版本。可选 `OCR_API_TOKEN` Bearer 鉴权，仅内网暴露。
- Java 负责 PDF 渲染与页映射：PDFBox 渲染 200 DPI PNG，只有「文本层为空/过短且页面有图像」的页才调用侧车；页图只在内存/临时文件短暂存在，调用后删除，不写对象存储。
- 配置默认关闭：`KB_OCR_ENABLED=false`、`KB_OCR_BASE_URL`、`KB_OCR_TOKEN`、`KB_OCR_MAX_PAGES=100`、`KB_OCR_TIMEOUT_MS=20000`、`KB_OCR_TOTAL_BUDGET_MS=480000`、`KB_OCR_MIN_TEXT_CHARS=10`、`KB_OCR_DPI=200`。
- 限额：超过 100 页需 OCR → `OCR_PAGE_LIMIT` 永久失败，不静默漏页；单页 20s、整篇 8 分钟；不做置信度过滤，`confidence` 只写日志/审计。
- 失败语义：连接/5xx/单页超时重试 2 次，仍失败按可重试错误进入摄取阶梯；OCR 关闭或不可用且整篇无文本 → 维持 `DOCUMENT_EMPTY`。
- 可观测性：`kb_documents.ocr_pages` 记录本次识别页数（0 表示未走 OCR），文档列表展示「OCR」标记；不新增置信度列。
- 部署：`docker-compose` 增加 `ocr` 服务与 healthcheck；CI 增加 `ocr` 服务的 pytest 与镜像构建；Java 测试使用假 OCR HTTP 服务，真实侧车全链路不进每次 CI。

## Considered Options（被否的备选）

- **进程内 Tesseract** — 中文准确率与资源隔离都不如侧车，native 依赖还增加跨平台成本。
- **PaddleOCR 侧车** — 中文效果标杆，但镜像与启动成本高；首版用 RapidOCR，接口保持引擎无关，后续可替换。
- **云端 OCR** — 数据出境、凭证与出站策略都要重做，不符合自托管定位。
- **所有 PDF 强制 OCR** — 文本型 PDF 成本翻倍且可能降低质量；只对无文本页兜底。
- **按置信度丢弃低分文本** — 会静默丢内容；首版只记录不拦截。
- **把 OCR 结果与页图写入对象存储** — 派生物与原文职责混淆，且扩大数据面。

## Consequences

- 多一个常驻服务、镜像与部署文档；本地 compose 与 CI 各多一步。
- 扫描件质量受 RapidOCR 与扫描分辨率限制；手写、复杂版式、表格仍不可用。
- 100 页扫描件的验收目标是 ≤10 分钟，与文本型 PDF 的 ≤5 分钟分开；OCR 页数上限保证恶意/超大扫描件不会无限占用 worker。
- `kb_documents` 增加 `ocr_pages` 列（up-only 迁移），文档列表与类型定义同步。
- 模块图、`CONTEXT.md` 与知识库 spec 需要加入 OCR 术语与边界。
