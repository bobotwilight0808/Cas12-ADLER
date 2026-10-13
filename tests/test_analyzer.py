import cv2
import numpy as np

from strip_reader.analyzer import analyze_canonical
from strip_reader.config import DEFAULT_THRESHOLDS, TEMPLATES


def test_synthetic_single_positive():
    image = np.full((900, 120, 3), 210, dtype=np.uint8)
    cv2.line(image, (18, int(0.46 * 900)), (102, int(0.46 * 900)), (20, 20, 150), 5)
    cv2.line(image, (18, int(0.52 * 900)), (102, int(0.52 * 900)), (20, 20, 180), 5)

    result = analyze_canonical(image, TEMPLATES["single"], DEFAULT_THRESHOLDS)

    assert result["test_validity"] == "valid"
    assert result["single_result"] == "positive"
    assert result["line_scores"]["C"]["present"] is True
    assert result["line_scores"]["T"]["present"] is True


def test_synthetic_positive_without_control_is_valid():
    image = np.full((900, 120, 3), 210, dtype=np.uint8)
    cv2.line(image, (18, int(0.46 * 900)), (102, int(0.46 * 900)), (20, 20, 150), 5)

    result = analyze_canonical(image, TEMPLATES["single"], DEFAULT_THRESHOLDS)

    assert result["test_validity"] == "valid"
    assert result["single_result"] == "positive"


def test_synthetic_invalid_when_no_lines_are_visible():
    image = np.full((900, 120, 3), 210, dtype=np.uint8)

    result = analyze_canonical(image, TEMPLATES["dual"], DEFAULT_THRESHOLDS)

    assert result["test_validity"] == "invalid_no_lines"
    assert result["targets"]["T1"] is None
    assert result["targets"]["T2"] is None
