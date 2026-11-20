# ocr/ — RapidOCR 侧车

知识库扫描件 PDF 的逐页 OCR 服务（ADR-0014）。Java 负责 PDF 渲染、页数/预算与失败分类；本服务只做「单页图片 → 文本」。

## API

### `POST /ocr`

- `Content-Type: image/png`，body 为单页 PNG 原始字节（≤20 MB）。
- 可选鉴权：配置 `OCR_API_TOKEN` 后要求 `Authorization: Bearer <token>`。
- 200：

```json
{
  "text": "第一行\n第二行",
  "confidence": 0.92,
  "lines": [{ "text": "第一行", "confidence": 0.95 }]
}
```

- 400 `INVALID_IMAGE`、401 `UNAUTHORIZED`、413 超限、500 `OCR_FAILED`、503 `UNAVAILABLE`。

### `GET /health`

`{ "ok": true, "engine": "rapidocr", "version": "<pinned>" }`；模型未就绪返回 503。

## 本地运行

```bash
cd ocr
python3.12 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
uvicorn app.main:app --port 8000
```

`docker compose --profile ocr up -d ocr` 走镜像构建；模型烤进镜像，不需要联网下载。

PyPI 直连较慢时用镜像源构建：

```bash
docker build \
  --build-arg PIP_INDEX_URL=http://mirrors.aliyun.com/pypi/simple \
  --build-arg PIP_TRUSTED_HOST=mirrors.aliyun.com \
  -t agent-core-ocr:local ./ocr
```

## 测试

```bash
cd ocr
pytest
```

`test_api.py` 用假引擎测 HTTP 契约；`test_rapidocr_smoke.py` 用真实 RapidOCR 读一张生成的英文图片。

## 配置

| 环境变量 | 说明 |
|---|---|
| `OCR_API_TOKEN` | 可选；设置后所有 `/ocr` 请求需 Bearer |
| `UVICORN_*` | 由 uvicorn 读取 |
