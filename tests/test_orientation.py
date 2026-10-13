import cv2
import numpy as np

from strip_reader.analyzer import analyze_canonical
from strip_reader.config import DEFAULT_THRESHOLDS, TEMPLATES
from strip_reader.orientation import choose_orientation


def test_dual_orientation_uses_expected_line_colors():
    image = np.full((900, 120, 3), 210, dtype=np.uint8)
    cv2.line(image, (18, int(0.46 * 900)), (102, int(0.46 * 900)), (20, 20, 180), 5)
    cv2.line(image, (18, int(0.60 * 900)), (102, int(0.60 * 900)), (180, 40, 40), 5)
    inverted = cv2.rotate(image, cv2.ROTATE_180)

    oriented, debug = choose_orientation(inverted, TEMPLATES["dual"])
    result = analyze_canonical(oriented, TEMPLATES["dual"], DEFAULT_THRESHOLDS)

    assert debug["orientation"] == "rotated_180_by_template_score"
    assert result["targets"]["T2"] == "positive"
    assert result["targets"]["T1"] == "negative"
