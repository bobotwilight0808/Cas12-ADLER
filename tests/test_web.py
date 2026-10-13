from io import BytesIO
from pathlib import Path

from PIL import Image

from strip_reader.web import create_app


def _image_bytes(image_format: str = "PNG") -> BytesIO:
    buffer = BytesIO()
    Image.new("RGB", (64, 96), "white").save(buffer, image_format)
    buffer.seek(0)
    return buffer


def test_web_index_renders_upload_tool():
    client = create_app().test_client()

    response = client.get("/")
    favicon_response = client.get("/static/favicon.svg")

    assert response.status_code == 200
    assert "Strip Reader" in response.get_data(as_text=True)
    assert '<html lang="en">' in response.get_data(as_text=True)
    assert 'id="file-input"' in response.get_data(as_text=True)
    assert favicon_response.status_code == 200
    assert favicon_response.content_type == "image/svg+xml; charset=utf-8"


def test_analyze_requires_valid_strip_type():
    client = create_app().test_client()

    response = client.post(
        "/api/analyze",
        data={"strip_type": "auto", "image": (_image_bytes(), "sample.png")},
        content_type="multipart/form-data",
    )

    assert response.status_code == 400
    assert "single-target or dual-target" in response.get_json()["error"]


def test_analyze_rejects_non_image_upload():
    client = create_app().test_client()

    response = client.post(
        "/api/analyze",
        data={"strip_type": "dual", "image": (BytesIO(b"not an image"), "sample.jpg")},
        content_type="multipart/form-data",
    )

    assert response.status_code == 400
    assert "Unable to read" in response.get_json()["error"]


def test_analyze_uses_temporary_file_and_returns_result():
    observed_path: Path | None = None

    def fake_analyzer(image_path, strip_type):
        nonlocal observed_path
        observed_path = Path(image_path)
        assert observed_path.exists()
        assert strip_type == "single"
        return {
            "readability": "ok",
            "strip_type": "single",
            "validity": "valid",
            "result": {"single": "negative", "targets": {"T": "negative", "T1": None, "T2": None}},
            "confidence": "high",
            "summary": {"strip_count": 1},
            "strips": [],
        }

    client = create_app(analyzer=fake_analyzer).test_client()
    response = client.post(
        "/api/analyze",
        data={"strip_type": "single", "image": (_image_bytes(), "sample.png")},
        content_type="multipart/form-data",
    )

    assert response.status_code == 200
    assert response.get_json()["result"]["single"] == "negative"
    assert observed_path is not None
    assert not observed_path.exists()
