from __future__ import annotations

from pathlib import Path
from typing import Any, Literal

from .analyzer import analyze_canonical
from .config import DEFAULT_THRESHOLDS, TEMPLATES, TemplateConfig, ThresholdConfig
from .detector import DetectionResult, detect_strips
from .io import load_image_bgr, save_image
from .orientation import choose_orientation


StripTypeArg = Literal["single", "dual", "auto"]


def analyze_image(
    image_path: str | Path,
    strip_type: StripTypeArg = "auto",
    thresholds: ThresholdConfig = DEFAULT_THRESHOLDS,
    debug_dir: str | Path | None = None,
) -> dict[str, Any]:
    path = Path(image_path)
    resolved_type = _resolve_strip_type(path, strip_type)
    if resolved_type is None:
        return _unreadable_result("unknown", "strip_type_unknown_use_flag_or_path_with_single_dual_marker")

    template = TEMPLATES[resolved_type]
    image = load_image_bgr(path)
    detections = detect_strips(
        image,
        output_size=(template.width, template.height),
        debug_dir=debug_dir,
        image_stem=path.stem,
    )
    if not detections:
        detections = [
            DetectionResult(False, None, [], "not_enough_colored_or_printed_pixels", {}),
        ]

    analyses: list[dict[str, Any]] = []
    for index, detection in enumerate(detections):
        if not detection.ok or detection.canonical_bgr is None:
            continue
        analyses.append(
            _analyze_detection(
                detection,
                template,
                thresholds,
                image_stem=path.stem,
                strip_index=index,
                strip_count=len(detections),
                debug_dir=debug_dir,
            )
        )

    if not analyses:
        reason = detections[0].reason
        return _unreadable_result(resolved_type, reason, strip_count=len(detections))

    return _aggregate_strips(analyses, resolved_type)


def _analyze_detection(
    detection: DetectionResult,
    template: TemplateConfig,
    thresholds: ThresholdConfig,
    image_stem: str,
    strip_index: int,
    strip_count: int,
    debug_dir: str | Path | None,
) -> dict[str, Any]:
    canonical_bgr, orientation_debug = choose_orientation(detection.canonical_bgr, template)
    if debug_dir is not None:
        suffix = "" if strip_count == 1 else f"_strip{strip_index + 1:02d}"
        save_image(Path(debug_dir) / f"{image_stem}{suffix}_canonical_oriented.jpg", canonical_bgr)
    analysis = analyze_canonical(canonical_bgr, template, thresholds)
    detection_details = {**detection.debug, **orientation_debug}
    return {
        "index": strip_index,
        "readability": "ok",
        "validity": analysis["test_validity"],
        "result": {
            "single": analysis["single_result"],
            "targets": analysis["targets"],
        },
        "confidence": analysis["confidence"],
        "line_scores": analysis["line_scores"],
        "detection": {
            "corners": detection.corners,
            "mode": detection.debug.get("detection_mode"),
            "orientation": detection.debug.get("detector_orientation", orientation_debug.get("orientation")),
            "reason": detection.reason,
            "details": detection_details,
        },
    }


def _aggregate_strips(strips: list[dict[str, Any]], strip_type: Literal["single", "dual"]) -> dict[str, Any]:
    selected = _select_representative_strip(strips, strip_type)
    strip_count = len(strips)
    if strip_type == "single":
        positive = any(strip.get("result", {}).get("single") == "positive" for strip in strips)
        negative = all(strip.get("result", {}).get("single") == "negative" for strip in strips)
        single_result = "positive" if positive else "negative" if negative else None
        targets = {"T": single_result, "T1": None, "T2": None}
    else:
        targets = {
            "T1": _aggregate_target(strips, "T1"),
            "T2": _aggregate_target(strips, "T2"),
            "T": None,
        }
        single_result = None

    if all(strip.get("validity") == "invalid_no_lines" for strip in strips):
        validity = "invalid_no_lines"
    else:
        validity = "valid"

    confidence = "low" if any(strip.get("confidence") == "low" for strip in strips) else "high"
    return {
        "readability": "ok",
        "strip_type": strip_type,
        "validity": validity,
        "result": {
            "single": single_result,
            "targets": targets,
        },
        "confidence": confidence,
        "summary": {
            "strip_count": strip_count,
            "selected_strip_index": selected.get("index"),
            "aggregation": "any_positive" if strip_count > 1 else "single_strip",
            "reason": "ok",
        },
        "strips": strips,
    }


def _aggregate_target(strips: list[dict[str, Any]], target_name: str) -> str | None:
    values = [strip.get("result", {}).get("targets", {}).get(target_name) for strip in strips]
    if any(value == "positive" for value in values):
        return "positive"
    if all(value == "negative" for value in values):
        return "negative"
    return None


def _select_representative_strip(strips: list[dict[str, Any]], strip_type: Literal["single", "dual"]) -> dict[str, Any]:
    if strip_type == "single":
        return max(strips, key=lambda strip: _score_value(strip, "T"))
    return max(strips, key=lambda strip: _score_value(strip, "T1") + _score_value(strip, "T2"))


def _score_value(analysis: dict[str, Any], line_name: str) -> float:
    score = analysis.get("line_scores", {}).get(line_name, {}).get("score")
    return float(score) if score is not None else float("-inf")


def _unreadable_result(
    strip_type: Literal["single", "dual"] | Literal["unknown"],
    reason: str,
    strip_count: int = 0,
) -> dict[str, Any]:
    return {
        "readability": "unreadable_photo",
        "strip_type": strip_type,
        "validity": "not_evaluated",
        "result": {
            "single": None,
            "targets": {"T": None, "T1": None, "T2": None},
        },
        "confidence": "low",
        "summary": {
            "strip_count": strip_count,
            "selected_strip_index": None,
            "aggregation": "none",
            "reason": reason,
        },
        "strips": [],
    }


def _resolve_strip_type(path: Path, requested: StripTypeArg) -> Literal["single", "dual"] | None:
    if requested in ("single", "dual"):
        return requested
    path_text = str(path)
    if "single" in path_text.lower():
        return "single"
    if "dual" in path_text.lower():
        return "dual"
    return None
