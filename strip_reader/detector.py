from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from .io import save_image


@dataclass
class DetectionResult:
    ok: bool
    canonical_bgr: np.ndarray | None
    corners: list[list[float]]
    reason: str
    debug: dict[str, Any]


def detect_strips(
    image_bgr: np.ndarray,
    output_size: tuple[int, int],
    debug_dir: str | Path | None = None,
    image_stem: str = "image",
) -> list[DetectionResult]:
    """Find the strip and warp it into canonical strip coordinates.

    The detector uses saturated printed/line pixels to estimate the strip axis,
    then takes a generous rotated crop. If a photo contains multiple parallel
    strips, colored/printed components are grouped first and each group is
    cropped independently.
    """

    height, width = image_bgr.shape[:2]
    max_dim = max(height, width)
    scale = 1600.0 / max_dim if max_dim > 1600 else 1.0
    small = cv2.resize(image_bgr, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    mask = _content_mask(small)
    points = cv2.findNonZero(mask)
    if points is None or len(points) < 80:
        return [DetectionResult(False, None, [], "not_enough_colored_or_printed_pixels", {})]

    grouped_detections = _detect_grouped_strips(image_bgr, mask, scale, output_size)
    if len(grouped_detections) > 1:
        detections = _with_candidate_debug(grouped_detections, "component_grouping")
        if debug_dir is not None:
            _write_multi_debug(debug_dir, image_stem, image_bgr, mask, detections)
        return detections

    all_points = points.reshape(-1, 2).astype(np.float32)
    single_detection = _detection_from_points(
        image_bgr,
        all_points,
        scale,
        output_size,
        extra_debug={"detection_mode": "single_global_mask"},
    )
    if single_detection.ok:
        detections = _with_candidate_debug([single_detection], "single_global_mask")
        if debug_dir is not None:
            _write_debug(
                debug_dir,
                image_stem,
                image_bgr,
                mask,
                detections[0].corners,
                detections[0].canonical_bgr,
            )
        return detections

    if len(grouped_detections) == 1:
        detections = _with_candidate_debug(grouped_detections, "component_grouping")
        if debug_dir is not None:
            _write_multi_debug(debug_dir, image_stem, image_bgr, mask, detections)
        return detections

    return [
        DetectionResult(
            False,
            None,
            [],
            single_detection.reason,
            {
                **single_detection.debug,
                "detection_mode": "single_global_mask",
                "grouped_candidate_count": len(grouped_detections),
            },
        )
    ]


def _detection_from_points(
    image_bgr: np.ndarray,
    points_xy: np.ndarray,
    scale: float,
    output_size: tuple[int, int],
    extra_debug: dict[str, Any] | None = None,
) -> DetectionResult:
    center, axis, minor_axis, major_extent, minor_extent = _robust_axis(points_xy)
    if major_extent < 80 or major_extent / max(minor_extent, 1.0) < 2.5:
        return DetectionResult(
            False,
            None,
            [],
            "content_pixels_do_not_form_a_long_strip",
            {
                "major_extent": float(major_extent / scale),
                "minor_extent": float(minor_extent / scale),
                **(extra_debug or {}),
            },
        )

    strip_length = max(major_extent * 1.45, minor_extent * 8.0, 260.0 * scale)
    strip_width = max(minor_extent * 2.25, strip_length * 0.105, 34.0 * scale)
    strip_length = min(strip_length, max(image_bgr.shape[:2]) * scale * 1.05)
    strip_width = min(strip_width, strip_length * 0.22)

    corners_small = _rectangle_corners(center, axis, minor_axis, strip_length, strip_width)
    corners = (corners_small / scale).astype(np.float32)
    canonical = _warp_rect(image_bgr, corners, output_size)
    canonical, orientation_debug = _orient_sample_pad_down(canonical)

    debug = {
        "scale": scale,
        "major_extent": float(major_extent / scale),
        "minor_extent": float(minor_extent / scale),
        "estimated_length": float(strip_length / scale),
        "estimated_width": float(strip_width / scale),
        **orientation_debug,
        **(extra_debug or {}),
    }

    return DetectionResult(True, canonical, corners.tolist(), "ok", debug)


def _detect_grouped_strips(
    image_bgr: np.ndarray,
    mask: np.ndarray,
    scale: float,
    output_size: tuple[int, int],
) -> list[DetectionResult]:
    count, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, connectivity=8)
    components = _component_infos(count, stats, centroids)
    if len(components) < 2:
        return []

    axis = _estimate_component_axis(components)
    groups = _cluster_components_by_cross_axis(components, axis, mask.shape)
    detections: list[DetectionResult] = []
    for group_index, group in enumerate(groups):
        if len(group) < 2:
            continue
        group_labels = {component["label"] for component in group}
        group_mask = np.isin(labels, list(group_labels)).astype(np.uint8) * 255
        points = cv2.findNonZero(group_mask)
        if points is None or len(points) < 80:
            continue
        detection = _detection_from_points(
            image_bgr,
            points.reshape(-1, 2).astype(np.float32),
            scale,
            output_size,
            extra_debug={
                "detection_mode": "component_grouping",
                "component_group_index": group_index,
                "component_count": len(group),
            },
        )
        if detection.ok:
            detections.append(detection)
    return _sort_detections_spatially(detections)


def _component_infos(
    count: int,
    stats: np.ndarray,
    centroids: np.ndarray,
) -> list[dict[str, float]]:
    components: list[dict[str, float]] = []
    for label in range(1, count):
        area = float(stats[label, cv2.CC_STAT_AREA])
        if area < 20:
            continue
        width = float(stats[label, cv2.CC_STAT_WIDTH])
        height = float(stats[label, cv2.CC_STAT_HEIGHT])
        components.append(
            {
                "label": label,
                "area": area,
                "x": float(stats[label, cv2.CC_STAT_LEFT]),
                "y": float(stats[label, cv2.CC_STAT_TOP]),
                "width": width,
                "height": height,
                "cx": float(centroids[label][0]),
                "cy": float(centroids[label][1]),
            }
        )
    return components


def _estimate_component_axis(components: list[dict[str, float]]) -> np.ndarray:
    horizontal_score = sum(component["area"] * component["width"] for component in components)
    vertical_score = sum(component["area"] * component["height"] for component in components)
    if horizontal_score >= vertical_score:
        return np.array([1.0, 0.0], dtype=np.float32)
    return np.array([0.0, 1.0], dtype=np.float32)


def _cluster_components_by_cross_axis(
    components: list[dict[str, float]],
    axis: np.ndarray,
    image_shape: tuple[int, int],
) -> list[list[dict[str, float]]]:
    minor_axis = np.array([-axis[1], axis[0]], dtype=np.float32)
    for component in components:
        center = np.array([component["cx"], component["cy"]], dtype=np.float32)
        component["cross"] = float(center @ minor_axis)

    cross_sizes = [
        component["height"] if abs(axis[0]) >= abs(axis[1]) else component["width"]
        for component in components
    ]
    gap_threshold = max(40.0, float(np.median(cross_sizes) * 4.0), float(min(image_shape[:2]) * 0.045))
    ordered = sorted(components, key=lambda component: component["cross"])
    groups: list[list[dict[str, float]]] = [[ordered[0]]]
    for component in ordered[1:]:
        if component["cross"] - groups[-1][-1]["cross"] > gap_threshold:
            groups.append([component])
        else:
            groups[-1].append(component)
    return [group for group in groups if sum(component["area"] for component in group) >= 80]


def _sort_detections_spatially(detections: list[DetectionResult]) -> list[DetectionResult]:
    def sort_key(detection: DetectionResult) -> tuple[float, float]:
        corners = np.array(detection.corners, dtype=np.float32)
        center = corners.mean(axis=0)
        return float(center[1]), float(center[0])

    return sorted(detections, key=sort_key)


def _with_candidate_debug(detections: list[DetectionResult], detection_mode: str) -> list[DetectionResult]:
    output: list[DetectionResult] = []
    for index, detection in enumerate(detections):
        debug = {
            **detection.debug,
            "detection_mode": detection.debug.get("detection_mode", detection_mode),
            "strip_index": index,
            "strip_count": len(detections),
        }
        output.append(
            DetectionResult(
                detection.ok,
                detection.canonical_bgr,
                detection.corners,
                detection.reason,
                debug,
            )
        )
    return output


def _content_mask(image_bgr: np.ndarray) -> np.ndarray:
    hsv = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    saturation = hsv[:, :, 1]
    value = hsv[:, :, 2]

    saturated = (saturation > 45) & (value > 35)
    if int(np.count_nonzero(saturated)) < 80:
        dark_print = gray < np.percentile(gray, 4)
        mask = (saturated | dark_print).astype(np.uint8) * 255
    else:
        mask = saturated.astype(np.uint8) * 255

    open_kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, open_kernel)
    mask = _keep_reasonable_components(mask)

    close_kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (11, 11))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, close_kernel, iterations=1)
    return mask


def _keep_reasonable_components(mask: np.ndarray) -> np.ndarray:
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    output = np.zeros_like(mask)
    image_area = mask.shape[0] * mask.shape[1]
    for label in range(1, count):
        area = stats[label, cv2.CC_STAT_AREA]
        if 8 <= area <= image_area * 0.05:
            output[labels == label] = 255
    return output


def _robust_axis(points_xy: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray, float, float]:
    mean = points_xy.mean(axis=0)
    centered = points_xy - mean
    cov = np.cov(centered.T)
    eigvals, eigvecs = np.linalg.eigh(cov)
    order = np.argsort(eigvals)[::-1]
    axis = eigvecs[:, order[0]].astype(np.float32)
    axis = axis / np.linalg.norm(axis)
    minor_axis = np.array([-axis[1], axis[0]], dtype=np.float32)

    major_projection = centered @ axis
    minor_projection = centered @ minor_axis
    major_low, major_high = np.percentile(major_projection, [1, 99])
    minor_low, minor_high = np.percentile(minor_projection, [1, 99])
    strip_center = mean + axis * ((major_low + major_high) / 2.0) + minor_axis * (
        (minor_low + minor_high) / 2.0
    )
    return (
        strip_center.astype(np.float32),
        axis,
        minor_axis,
        float(major_high - major_low),
        float(minor_high - minor_low),
    )


def _rectangle_corners(
    center: np.ndarray,
    axis: np.ndarray,
    minor_axis: np.ndarray,
    length: float,
    width: float,
) -> np.ndarray:
    half_l = length / 2.0
    half_w = width / 2.0
    return np.array(
        [
            center - axis * half_l - minor_axis * half_w,
            center - axis * half_l + minor_axis * half_w,
            center + axis * half_l + minor_axis * half_w,
            center + axis * half_l - minor_axis * half_w,
        ],
        dtype=np.float32,
    )


def _warp_rect(
    image_bgr: np.ndarray,
    corners: np.ndarray,
    output_size: tuple[int, int],
) -> np.ndarray:
    output_width, output_height = output_size
    destination = np.array(
        [
            [0, 0],
            [output_width - 1, 0],
            [output_width - 1, output_height - 1],
            [0, output_height - 1],
        ],
        dtype=np.float32,
    )
    matrix = cv2.getPerspectiveTransform(corners.astype(np.float32), destination)
    return cv2.warpPerspective(image_bgr, matrix, (output_width, output_height))


def _orient_sample_pad_down(canonical_bgr: np.ndarray) -> tuple[np.ndarray, dict[str, Any]]:
    """Orient the strip so the blue arrow/sample pad is usually near the bottom."""

    hsv = cv2.cvtColor(canonical_bgr, cv2.COLOR_BGR2HSV)
    hue = hsv[:, :, 0]
    saturation = hsv[:, :, 1].astype(np.float32)
    width = canonical_bgr.shape[1]
    x1 = int(width * 0.25)
    x2 = int(width * 0.75)
    blue_mask = (hue > 85) & (hue < 135) & (saturation > 60)
    red_mask = ((hue < 12) | (hue > 165)) & (saturation > 45)

    blue_profile = np.sum(blue_mask[:, x1:x2], axis=1).astype(np.float32)
    red_profile = np.sum(red_mask[:, x1:x2], axis=1).astype(np.float32)
    if np.max(blue_profile) > 5 and np.max(red_profile) > 2:
        blue_y = int(np.argmax(cv2.GaussianBlur(blue_profile.reshape(-1, 1), (1, 21), 0).reshape(-1)))
        red_y = int(np.argmax(cv2.GaussianBlur(red_profile.reshape(-1, 1), (1, 15), 0).reshape(-1)))
        if blue_y < red_y:
            return cv2.rotate(canonical_bgr, cv2.ROTATE_180), {
                "detector_orientation": "rotated_180_sample_pad_down",
                "orientation_signal": "blue_before_red",
                "blue_peak_y": blue_y,
                "red_peak_y": red_y,
            }
        return canonical_bgr, {
            "detector_orientation": "sample_pad_down",
            "orientation_signal": "blue_after_red",
            "blue_peak_y": blue_y,
            "red_peak_y": red_y,
        }

    blue = blue_mask.astype(np.float32) * saturation
    height = canonical_bgr.shape[0]
    top_score = float(np.mean(blue[: height // 3]))
    bottom_score = float(np.mean(blue[height * 2 // 3 :]))
    if top_score > bottom_score * 1.15:
        return cv2.rotate(canonical_bgr, cv2.ROTATE_180), {
            "detector_orientation": "rotated_180_sample_pad_down",
            "orientation_signal": "top_blue_dominant",
            "top_blue_score": top_score,
            "bottom_blue_score": bottom_score,
        }
    return canonical_bgr, {
        "detector_orientation": "sample_pad_down",
        "orientation_signal": "bottom_blue_dominant_or_tie",
        "top_blue_score": top_score,
        "bottom_blue_score": bottom_score,
    }


def _write_debug(
    debug_dir: str | Path,
    image_stem: str,
    image_bgr: np.ndarray,
    mask: np.ndarray,
    corners: np.ndarray,
    canonical: np.ndarray,
) -> None:
    output_dir = Path(debug_dir)
    overlay = image_bgr.copy()
    cv2.polylines(overlay, [np.round(corners).astype(np.int32)], True, (0, 255, 255), 8)
    save_image(output_dir / f"{image_stem}_strip_overlay.jpg", overlay)
    save_image(output_dir / f"{image_stem}_canonical.jpg", canonical)
    save_image(output_dir / f"{image_stem}_mask.jpg", cv2.cvtColor(mask, cv2.COLOR_GRAY2BGR))


def _write_multi_debug(
    debug_dir: str | Path,
    image_stem: str,
    image_bgr: np.ndarray,
    mask: np.ndarray,
    detections: list[DetectionResult],
) -> None:
    output_dir = Path(debug_dir)
    overlay = image_bgr.copy()
    for detection in detections:
        index = int(detection.debug.get("strip_index", 0))
        corners = np.round(np.array(detection.corners, dtype=np.float32)).astype(np.int32)
        cv2.polylines(overlay, [corners], True, (0, 255, 255), 8)
        label_position = tuple(corners[0].tolist())
        cv2.putText(
            overlay,
            str(index + 1),
            label_position,
            cv2.FONT_HERSHEY_SIMPLEX,
            2.0,
            (0, 0, 255),
            5,
            cv2.LINE_AA,
        )
        if detection.canonical_bgr is not None:
            save_image(output_dir / f"{image_stem}_strip{index + 1:02d}_canonical.jpg", detection.canonical_bgr)

    save_image(output_dir / f"{image_stem}_strips_overlay.jpg", overlay)
    save_image(output_dir / f"{image_stem}_mask.jpg", cv2.cvtColor(mask, cv2.COLOR_GRAY2BGR))
