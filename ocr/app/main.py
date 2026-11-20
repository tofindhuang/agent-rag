"""Internal OCR API consumed by the Java ingestion worker (ADR-0014).

`POST /ocr` accepts one PNG page as raw bytes and returns text + confidence.
`GET /health` is the compose readiness probe. No image is persisted.
"""

from __future__ import annotations

import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .engine import Engine, OcrLine, RapidOcrEngine

MAX_IMAGE_BYTES = 20 * 1024 * 1024


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": {"code": code, "message": message}})


def create_app(engine: Engine | None = None, token: str | None = None) -> FastAPI:
    expected_token = token if token is not None else os.environ.get("OCR_API_TOKEN", "")
    provided_engine = engine

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.engine = provided_engine or RapidOcrEngine()
        yield

    app = FastAPI(title="agent-core OCR", version="1", lifespan=lifespan)
    app.state.expected_token = expected_token

    def authorized(request: Request) -> bool:
        if not expected_token:
            return True
        return request.headers.get("authorization") == f"Bearer {expected_token}"

    @app.get("/health")
    def health(request: Request):
        current: Engine = request.app.state.engine
        if not current.ready:
            return _error(503, "UNAVAILABLE", "OCR engine is not ready")
        return {"ok": True, "engine": current.name, "version": current.version}

    @app.post("/ocr")
    async def ocr(request: Request):
        if not authorized(request):
            return _error(401, "UNAUTHORIZED", "invalid OCR token")
        body = await request.body()
        if not body:
            return _error(400, "INVALID_IMAGE", "image body is required")
        if len(body) > MAX_IMAGE_BYTES:
            return _error(413, "INVALID_IMAGE", "image exceeds the 20 MB limit")
        current: Engine = request.app.state.engine
        try:
            lines = current.recognize(body)
        except ValueError as exc:
            return _error(400, "INVALID_IMAGE", str(exc) or "cannot decode image")
        except Exception as exc:  # pragma: no cover - defensive boundary
            return _error(500, "OCR_FAILED", f"OCR failed: {exc}")
        return _payload(lines)

    return app


def _payload(lines: list[OcrLine]) -> dict:
    if not lines:
        return {"text": "", "confidence": 0.0, "lines": []}
    confidence = sum(line.confidence for line in lines) / len(lines)
    return {
        "text": "\n".join(line.text for line in lines),
        "confidence": round(confidence, 4),
        "lines": [
            {"text": line.text, "confidence": round(line.confidence, 4)}
            for line in lines
        ],
    }


app = create_app()
