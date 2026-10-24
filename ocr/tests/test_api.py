from fastapi.testclient import TestClient

from app.engine import OcrLine
from app.main import create_app


class FakeEngine:
    name = "fake"
    version = "0.0.1"
    ready = True

    def __init__(self, lines=None, error=None):
        self.lines = lines or []
        self.error = error

    def recognize(self, image: bytes):
        if self.error:
            raise self.error
        return self.lines


def client(engine=None, token=None):
    return TestClient(create_app(engine=engine or FakeEngine(), token=token))


def test_health_reports_the_engine():
    with client() as http:
        response = http.get("/health")
    assert response.status_code == 200
    assert response.json() == {"ok": True, "engine": "fake", "version": "0.0.1"}


def test_ocr_returns_text_and_mean_confidence():
    lines = [OcrLine("第一行", 0.9), OcrLine("第二行", 0.8)]
    with client(FakeEngine(lines)) as http:
        response = http.post("/ocr", content=b"png", headers={"content-type": "image/png"})
    assert response.status_code == 200
    body = response.json()
    assert body["text"] == "第一行\n第二行"
    assert body["confidence"] == 0.85
    assert [line["text"] for line in body["lines"]] == ["第一行", "第二行"]


def test_ocr_on_an_empty_result_is_not_an_error():
    with client(FakeEngine([])) as http:
        response = http.post("/ocr", content=b"png")
    assert response.status_code == 200
    assert response.json() == {"text": "", "confidence": 0.0, "lines": []}


def test_token_is_enforced_when_configured():
    with client(token="secret") as http:
        assert http.post("/ocr", content=b"png").status_code == 401
        ok = http.post(
            "/ocr",
            content=b"png",
            headers={"authorization": "Bearer secret"},
        )
        assert ok.status_code == 200


def test_invalid_image_and_empty_body_are_client_errors():
    with client(FakeEngine(error=ValueError("cannot decode"))) as http:
        invalid = http.post("/ocr", content=b"not-an-image")
        empty = http.post("/ocr", content=b"")
    assert invalid.status_code == 400
    assert invalid.json()["error"]["code"] == "INVALID_IMAGE"
    assert empty.status_code == 400


def test_engine_failure_is_a_server_error():
    with client(FakeEngine(error=RuntimeError("boom"))) as http:
        response = http.post("/ocr", content=b"png")
    assert response.status_code == 500
    assert response.json()["error"]["code"] == "OCR_FAILED"
