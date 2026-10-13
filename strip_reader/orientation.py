from __future__ import annotations

from typing import Any

import cv2
import numpy as np

from .config import TemplateConfig


EXPECTED_LINE_COLORS = {
    "single": {"T": "any", "C": "any"},
    "dual": {"T2": "red", "T1": "blue", "C": "any"},
}


def choose_orientation(canonical_bgr: np.ndarray, template: TemplateConfig) -> tuple[np.ndarray, dict[str, Any]]:
    """Choose between the current strip orientation and a 180-degree rotation."""

    if template.strip_type != "dual":
        return canonical_bgr, {"orientation": "detector_default", "score_current": None, "score_rotated": None}

    rotated = cv2.rotate(canonical_bgr, cv2.ROTATE_180)
    current_score = _dual_orientation_scores(canonical_bgr, template)
    rotated_score = _dual_orientation_scores(rotated, template)
    current_decision_score = _orientation_decision_score(current_score, rotated_score)
    rotated_decision_score = _orientation_decision_score(rotated_score, current_score)
    if rotated_decision_score > current_decision_score * 1.25:
        return rotated, {
            "orientation": "rotated_180_by_template_score",
            "score_current": current_decision_score,
            "score_rotated": rotated_decision_score,
            "score_current_detail": current_score,
            "score_rotated_detail": rotated_score,
        }
    return canonical_bgr, {
        "orientation": "detector_default",
        "score_current": current_decision_score,
        "score_rotated": rotated_decision_score,
        "score_current_detail": current_score,
        "score_rotated_detail": rotated_score,
    }


def _dual_orientation_scores(canonical_bgr: np.ndarray, template: TemplateConfig) -> dict[str, float]:
    profiles = _color_profiles(canonical_bgr)
    target_score = 0.0
    control_score = 0.0
    details: dict[str, float] = {}
    for line_name, window in template.lines.items():
        color = EXPECTED_LINE_COLORS[template.strip_type].get(line_name, "any")
        score = max(0.0, _window_score(profiles[color], window.y, window.half_height))
        details[line_name] = float(score)
        if line_name == "C":
            control_score += score
        else:
            target_score += score
    details["target_total"] = float(target_score)
    details["control_total"] = float(control_score)
    return details


def _orientation_decision_score(score: dict[str, float], other_score: dict[str, float]) -> float:
    if max(score["target_total"], other_score["target_total"]) >= 8.0:
        return score["target_total"]
    return score["control_total"]


def _color_profiles(canonical_bgr: np.ndarray) -> dict[str, np.ndarray]:
    hsv = cv2.cvtColor(canonical_bgr, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(canonical_bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    hue = hsv[:, :, 0]
    saturation = hsv[:, :, 1].astype(np.float32)
    width = canonical_bgr.shape[1]
    x1 = max(0, int(width * 0.14))
    x2 = min(width, int(width * 0.86))

    central_sat = saturation[:, x1:x2]
    central_gray = gray[:, x1:x2]
    central_hue = hue[:, x1:x2]
    darkness = np.maximum(0.0, np.percentile(central_gray, 80, axis=1, keepdims=True) - central_gray)
    any_image = 0.72 * central_sat + 0.28 * darkness
    red_image = (((central_hue < 12) | (central_hue > 165)).astype(np.float32)) * central_sat
    blue_image = (((central_hue > 85) & (central_hue < 135)).astype(np.float32)) * central_sat

    return {
        "any": _smooth(np.percentile(any_image, 92, axis=1)),
        "red": _smooth(np.percentile(red_image, 92, axis=1)),
        "blue": _smooth(np.percentile(blue_image, 92, axis=1)),
    }


def _smooth(profile: np.ndarray) -> np.ndarray:
    return cv2.GaussianBlur(profile.astype(np.float32).reshape(-1, 1), (1, 17), 0).reshape(-1)


def _window_score(profile: np.ndarray, y_ratio: float, half_height_ratio: float) -> float:
    height = len(profile)
    center = int(round(y_ratio * height))
    half = max(4, int(round(half_height_ratio * height)))
    y1 = max(0, center - half)
    y2 = min(height, center + half + 1)
    bg_gap = max(3, half // 2)
    bg_half = max(6, half)
    bg_values = np.concatenate(
        [
            profile[max(0, y1 - bg_gap - bg_half) : max(0, y1 - bg_gap)],
            profile[min(height, y2 + bg_gap) : min(height, y2 + bg_gap + bg_half)],
        ]
    )
    if len(bg_values) == 0:
        bg_values = profile
    return float(np.max(profile[y1:y2]) - np.median(bg_values))
