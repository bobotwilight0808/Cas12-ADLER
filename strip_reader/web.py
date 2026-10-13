from __future__ import annotations

import tempfile
from io import BytesIO
from pathlib import Path
from typing import Any, Callable

from flask import Flask, jsonify, render_template, request
from PIL import Image, UnidentifiedImageError

from .reader import analyze_image


PROJECT_ROOT = Path(__file__).resolve().parents[1]
MAX_UPLOAD_BYTES = 15 * 1024 * 1024
ALLOWED_FORMATS = {"JPEG": ".jpg", "PNG": ".png", "WEBP": ".webp"}


def create_app(
    analyzer: Callable[..., dict[str, Any]] = analyze_image,
) -> Flask:
    app = Flask(
        __name__,
        static_folder=str(PROJECT_ROOT / "static"),
        template_folder=str(PROJECT_ROOT / "templates"),
    )
    app.config["MAX_CONTENT_LENGTH"] = MAX_UPLOAD_BYTES
    app.config["ANALYZER"] = analyzer

    @app.get("/")
    def index() -> str:
        return render_template("index.html")

    @app.get("/api/health")
    def health() -> Any:
        return jsonify({"status": "ok"})

    @app.post("/api/analyze")
    def analyze() -> Any:
        strip_type = request.form.get("strip_type", "")
        if strip_type not in {"single", "dual"}:
            return _error("Select a single-target or dual-target strip.", 400)

        upload = request.files.get("image")
        if upload is None or not upload.filename:
            return _error("Select a test strip image.", 400)

        image_bytes = upload.read()
        if not image_bytes:
            return _error("The uploaded image is empty.", 400)

        try:
            image_format = _verify_image(image_bytes)
        except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
            return _error("Unable to read this image. Use JPG, PNG, or WebP.", 400)

        suffix = ALLOWED_FORMATS.get(image_format)
        if suffix is None:
            return _error("Unsupported image format. Use JPG, PNG, or WebP.", 400)

        temp_path: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as temp_file:
                temp_file.write(image_bytes)
                temp_path = Path(temp_file.name)
            result = app.config["ANALYZER"](temp_path, strip_type=strip_type)
            return jsonify(result)
        except Exception:
            app.logger.exception("Strip analysis failed")
            return _error("Analysis failed. Try a clearer image.", 500)
        finally:
            if temp_path is not None:
                temp_path.unlink(missing_ok=True)

    @app.errorhandler(413)
    def upload_too_large(_: Exception) -> Any:
        return _error("Image size must not exceed 15 MB.", 413)

    return app


def _verify_image(image_bytes: bytes) -> str:
    with Image.open(BytesIO(image_bytes)) as image:
        image.verify()
        if image.width < 32 or image.height < 32:
            raise ValueError("Image is too small")
        return image.format or ""


def _error(message: str, status_code: int) -> tuple[Any, int]:
    return jsonify({"error": message}), status_code
