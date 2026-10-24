"""Engine boundary: the HTTP layer only knows text + confidence, not RapidOCR."""

from __future__ import annotations

from dataclasses import dataclass
from importlib import metadata
from typing import Protocol


@dataclass(frozen=True)
class OcrLine:
    text: str
    confidence: float


class Engine(Protocol):
    name: str
    version: str

    @property
    def ready(self) -> bool: ...

    def recognize(self, image: bytes) -> list[OcrLine]: ...


class RapidOcrEngine:
    """RapidOCR (ONNX Runtime) wrapper; models are bundled with the package."""

    name = "rapidocr"

    def __init__(self) -> None:
        from rapidocr_onnxruntime import RapidOCR

        self.version = metadata.version("rapidocr-onnxruntime")
        self._engine = RapidOCR()
        self._ready = True

    @property
    def ready(self) -> bool:
        return self._ready

    def recognize(self, image: bytes) -> list[OcrLine]:
        result, _ = self._engine(image)
        if not result:
            return []
        lines: list[OcrLine] = []
        for item in result:
            # RapidOCR item shape: [box, text, score].
            if len(item) < 3:
                continue
            text = str(item[1]).strip()
            if not text:
                continue
            lines.append(OcrLine(text=text, confidence=float(item[2])))
        return lines
