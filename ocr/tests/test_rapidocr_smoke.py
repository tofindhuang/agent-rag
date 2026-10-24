"""Real RapidOCR smoke test: proves the pinned engine can read a PNG."""

from io import BytesIO

from PIL import Image, ImageDraw

from app.engine import RapidOcrEngine


def test_rapidocr_reads_a_simple_image():
    image = Image.new("RGB", (600, 160), "white")
    draw = ImageDraw.Draw(image)
    draw.text((30, 50), "Hello OCR 123", fill="black")
    buffer = BytesIO()
    image.save(buffer, format="PNG")

    lines = RapidOcrEngine().recognize(buffer.getvalue())

    assert lines
    assert "OCR" in " ".join(line.text for line in lines)
    assert all(0.0 <= line.confidence <= 1.0 for line in lines)
