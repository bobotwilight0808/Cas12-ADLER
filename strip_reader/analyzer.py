from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

import cv2
import numpy as np

from .config import TemplateConfig, ThresholdConfig


@dataclass
class LineScore:
    score: float
    snr: float
    peak_y: int
    peak_y_ratio: float
    threshold: float
    present: bool
    window: list[int]
    background_window: list[int]


def analyze_canonical(
    canonical_bgr: np.ndarray,
    template: TemplateConfig,
    thresholds: ThresholdConfig,
) -> dict[str, Any]:
    profile = _line_profile(canonical_bgr)
    line_scores: dict[str, LineScore] = {}
    for line_name, window in template.lines.items():
        threshold = thresholds.control if line_name == "C" else thresholds.target
        line_scores[line_name] = _score_line(
            profile,
            window.y,
            window.half_height,
            threshold,
            peak_tolerance=window.peak_tolerance,
        )

    any_line_present = any(score.present for score in line_scores.values())
    target_results: dict[str, str | None] = {}
    if not any_line_present:
        validity = "invalid_no_lines"
        single_result = None
        for line_name in template.lines:
            if line_name != "C":
                target_results[line_name] = None
    else:
        validity = "valid"
        if template.strip_type == "single":
            target_results["T"] = "positive" if line_scores["T"].present else "negative"
            single_result = target_results["T"]
        else:
            single_result = None
            for line_name in ("T1", "T2"):
                target_results[line_name] = "positive" if line_scores[line_name].present else "negative"

    confidence = _confidence(line_scores, thresholds)
    return {
        "test_validity": validity,
        "single_result": single_result,
        "targets": {
            "T1": target_results.get("T1"),
            "T2": target_results.get("T2"),
            "T": target_results.get("T"),
        },
        "confidence": confidence,
        "line_scores": {name: asdict(score) for name, score in line_scores.items()},
    }


def _line_profile(canonical_bgr: np.ndarray) -> np.ndarray:
    hsv = cv2.cvtColor(canonical_bgr, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(canonical_bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    saturation = hsv[:, :, 1].astype(np.float32)

    width = canonical_bgr.shape[1]
    x1 = max(0, int(width * 0.14))
    x2 = min(width, int(width * 0.86))
    central_saturation = saturation[:, x1:x2]
    central_gray = gray[:, x1:x2]

    darkness = np.maximum(0.0, np.percentile(central_gray, 80, axis=1, keepdims=True) - central_gray)
    score_image = 0.72 * central_saturation + 0.28 * darkness
    profile = np.percentile(score_image, 92, axis=1)
    profile = cv2.GaussianBlur(profile.reshape(-1, 1), (1, 17), sigmaX=0, sigmaY=0).reshape(-1)
    return profile.astype(np.float32)


def _score_line(
    profile: np.ndarray,
    y_ratio: float,
    half_height_ratio: float,
    threshold: float,
    peak_tolerance: float | None = None,
) -> LineScore:
    height = len(profile)
    center = int(round(y_ratio * height))
    half = max(4, int(round(half_height_ratio * height)))
    y1 = max(0, center - half)
    y2 = min(height, center + half + 1)

    bg_gap = max(3, half // 2)
    bg_half = max(6, half)
    bg1a = max(0, y1 - bg_gap - bg_half)
    bg1b = max(0, y1 - bg_gap)
    bg2a = min(height, y2 + bg_gap)
    bg2b = min(height, y2 + bg_gap + bg_half)
    bg_values = np.concatenate([profile[bg1a:bg1b], profile[bg2a:bg2b]])
    if len(bg_values) == 0:
        bg_values = profile

    segment = profile[y1:y2]
    peak_offset = int(np.argmax(segment))
    peak_y = y1 + peak_offset
    background = float(np.median(bg_values))
    noise = float(np.std(bg_values) + 1e-6)
    score = float(segment[peak_offset] - background)
    snr = float(score / noise)
    peak_y_ratio = float(peak_y / max(height - 1, 1))
    peak_in_position = peak_tolerance is None or abs(peak_y_ratio - y_ratio) <= peak_tolerance
    return LineScore(
        score=score,
        snr=snr,
        peak_y=peak_y,
        peak_y_ratio=peak_y_ratio,
        threshold=threshold,
        present=score >= threshold and peak_in_position,
        window=[y1, y2],
        background_window=[bg1a, bg1b, bg2a, bg2b],
    )


def _confidence(line_scores: dict[str, LineScore], thresholds: ThresholdConfig) -> str:
    for score in line_scores.values():
        margin = max(score.threshold * thresholds.low_confidence_margin, 2.0)
        if abs(score.score - score.threshold) <= margin:
            return "low"
    return "high"
