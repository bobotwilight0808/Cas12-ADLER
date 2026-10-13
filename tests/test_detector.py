import cv2
import numpy as np

from strip_reader.detector import detect_strips


def test_detect_strips_splits_parallel_multi_strip_image():
    image = np.full((800, 1000, 3), 220, dtype=np.uint8)
    for y in (170, 380, 590):
        cv2.rectangle(image, (150, y - 28), (850, y + 28), (245, 245, 245), -1)
        cv2.rectangle(image, (170, y - 18), (250, y + 18), (210, 120, 20), -1)
        cv2.rectangle(image, (430, y - 18), (438, y + 18), (45, 45, 190), -1)
        cv2.rectangle(image, (485, y - 18), (493, y + 18), (45, 45, 190), -1)
        cv2.rectangle(image, (710, y - 18), (820, y + 18), (210, 120, 20), -1)

    detections = detect_strips(image, output_size=(120, 900))

    assert len(detections) == 3
    assert all(detection.ok for detection in detections)
    assert [detection.debug["strip_index"] for detection in detections] == [0, 1, 2]
