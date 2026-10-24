# 扫描件 OCR：RapidOCR 侧车与 PDF 逐页兜底

日期：2026-10-24
状态：实现中（tickets 10–12 完成；13 真实侧车验收待做）
关联需求：FR-2（知识库 / RAG）
关联 ADR：[0014](../adrs/0014-ocr-sidecar-rapidocr.md)
上游 spec：[2026-10-23-knowledge-base-rag.md](2026-10-23-knowledge-base-rag.md)

## Problem Statement

知识库首版只处理有文本层的 PDF。扫描版/图片型 PDF 抽取为空，最终以 `DOCUMENT_EMPTY` 永久失败；用户无法检索这类文档。需要在不拖慢文本型 PDF、不把页图落盘的前提下，让扫描件也能进入摄取、检索与引用。

## Decisions

1. **侧车**：新增 `ocr/`（Python 3.12 + FastAPI + `rapidocr_onnxruntime`），引擎无关的 HTTP 契约；模型烤进镜像，CPU 推理。
2. **职责**：Java 用 PDFBox 渲染 200 DPI PNG 并保持页码/页数/预算；侧车只做「单页图片 → 文本」。
3. **触发**：仅 PDF 逐页兜底——抽取文本 `strip().length < KB_OCR_MIN_TEXT_CHARS`（默认 10）且页面有图像时 OCR；有文本页不调用。
4. **默认关**：`KB_OCR_ENABLED=false`；关闭或侧车不可用时维持现有文本抽取语义，整篇无文本 → `DOCUMENT_EMPTY`。
5. **限额**：单页 20s、整篇 8 分钟、最多 100 页需 OCR；超页数 → `OCR_PAGE_LIMIT` 永久失败；单页最多试 2 次。
6. **质量**：不做置信度过滤，`confidence` 只写日志；首版不承诺手写/复杂版式/表格。
7. **可观测**：`kb_documents.ocr_pages` 记录识别页数（0 = 未走 OCR），文档列表展示「OCR」标记；不存页图、不存置信度。
8. **部署**：compose 增加 `ocr` 服务；CI 增加 OCR 服务的 pytest 与镜像构建；Java 测试用假 OCR HTTP 服务，真实侧车全链路不进每次 CI。

## 模块与契约

### `ocr/` 模块

```
ocr/
├── Dockerfile
├── pyproject.toml / requirements.txt
├── app/
│   ├── main.py          # FastAPI: POST /ocr, GET /health
│   └── engine.py        # RapidOCR 封装（可替换）
└── tests/               # pytest：假引擎契约 + RapidOCR 冒烟
```

`POST /ocr`

- Header：`Content-Type: image/png`；可选 `Authorization: Bearer <OCR_API_TOKEN>`。
- Body：单页 PNG 原始字节。
- 200：

```json
{
  "text": "识别出的正文，按行以换行拼接",
  "confidence": 0.92,
  "lines": [
    { "text": "第一行", "confidence": 0.95 },
    { "text": "第二行", "confidence": 0.89 }
  ]
}
```

- 400 `{ "error": { "code": "INVALID_IMAGE", "message": "..." } }`
- 500 `OCR_FAILED`；503 `UNAVAILABLE`（模型未就绪/过载）。
- 请求体上限 20 MB；不保存图片。

`GET /health` → `{ "ok": true, "engine": "rapidocr", "version": "<pinned>" }`；模型未加载完成返回 503。

### Java 配置（`KbProperties.Ocr`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `KB_OCR_ENABLED` | `false` | 总开关 |
| `KB_OCR_BASE_URL` | 空 | 如 `http://ocr:8000` |
| `KB_OCR_TOKEN` | 空 | 可选 Bearer |
| `KB_OCR_MAX_PAGES` | `100` | 需 OCR 的页数上限 |
| `KB_OCR_TIMEOUT_MS` | `20000` | 单页请求超时 |
| `KB_OCR_TOTAL_BUDGET_MS` | `480000` | 整篇 OCR 预算 |
| `KB_OCR_MIN_TEXT_CHARS` | `10` | 触发兜底的文本长度阈值 |
| `KB_OCR_DPI` | `200` | PDF 渲染 DPI |

### 摄取流程

```
PDFBox 抽页文本
  └─ 每页 text.strip().length < MIN_TEXT_CHARS 且页面有图像
       ├─ OCR 关闭/未配置 → 保持空页
       └─ 渲染 200 DPI PNG → POST /ocr（单页最多 2 次，20s/次）
             ├─ 成功 → 用 OCR 文本替换该页文本，ocrPages++
             ├─ 页数超限 → OCR_PAGE_LIMIT（永久）
             └─ 预算耗尽/仍失败 → OCR_TIMEOUT/OCR_UNAVAILABLE（可重试）
```

- 页图仅存在于临时文件/内存，调用后删除。
- 所有页处理完仍无文本 → `DOCUMENT_EMPTY`（现有逻辑）。
- 文本型 PDF 完全不进入 OCR 分支，AC1 保持 ≤5 分钟。

### 数据模型

新增迁移 `V20261024191000__add_kb_ocr_pages.up.sql`（`information_schema` 守卫，up-only）：

```sql
ALTER TABLE kb_documents ADD COLUMN ocr_pages INT NOT NULL DEFAULT 0 AFTER chunk_count;
```

- `KbDocument`、`KbDocumentDao.xml` 的列清单/insert/select、`KbDocumentView` 同步增加 `ocrPages`。
- 文档列表 API 返回 `ocrPages`，web 在行内显示「OCR · N 页」标记（`N>0` 时）。

### 错误码

| code | 语义 | 重试 |
|---|---|---|
| `OCR_PAGE_LIMIT` | 需 OCR 页数 > `MAX_PAGES` | 否（永久失败） |
| `OCR_UNAVAILABLE` | 侧车不可达/5xx/单页失败 | 是 |
| `OCR_TIMEOUT` | 单页超时或整篇预算耗尽 | 是 |
| `OCR_INVALID_IMAGE` | 侧车 400（渲染/编码异常） | 否（按永久失败记录） |

## 验收

- 扫描版中文 PDF（无文本层）开启 OCR 后可 `ready`，`kb_search` 命中，引用显示标题/段序号/页码；`ocr_pages > 0`。
- 100 页扫描件 OCR ≤10 分钟（真实侧车，单独验收；CI 用假 OCR 验证流程）。
- 文本型 PDF 行为与耗时不变（≤5 分钟），`ocr_pages = 0`。
- OCR 关闭：扫描件仍 `DOCUMENT_EMPTY`；OCR 服务不可用：按 `OCR_UNAVAILABLE` 重试，最终 `failed` + DLQ。
- 超过 100 页需 OCR：`OCR_PAGE_LIMIT`，不走重试。
- 页图不进入对象存储、不落业务磁盘；日志不含图片内容。
- `ocr/` pytest、镜像构建、Java 假 OCR 集成测试、web 类型与浏览器标记验证全部通过。

## Out of Scope

- DOCX/HTML 内嵌图片 OCR；手写体、印章、表格结构重建、版面分析。
- 按用户配置 OCR 凭证/语言；OCR 为部署级能力。
- GPU 推理、模型热更新、OCR 结果人工校正。
- 对已有文本层做强制 OCR（用户明确否决）。
- 图片型 PDF 的 OCR 质量评分/自动拒绝（只记 confidence 日志）。

## Risks

- RapidOCR 对低分辨率/复杂版式中文的识别率有限；质量不达标时按 ADR 换引擎，Java 契约不变。
- 100 页扫描件仍是 CPU 密集任务；即便有页数/预算上限，也会占用 worker 线程（per-user 串行已限制并发）。
- 新增长驻服务与镜像，部署与 CI 成本上升；本地开发需 `docker compose --profile ocr` 或显式启动。
