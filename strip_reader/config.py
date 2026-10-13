from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Literal


StripType = Literal["single", "dual"]


@dataclass(frozen=True)
class LineWindow:
    """A line position in normalized strip coordinates."""

    y: float
    half_height: float = 0.025
    peak_tolerance: float | None = None


@dataclass(frozen=True)
class TemplateConfig:
    strip_type: StripType
    width: int
    height: int
    lines: Dict[str, LineWindow]


@dataclass(frozen=True)
class ThresholdConfig:
    control: float = 1.0
    target: float = 6.0
    low_confidence_margin: float = 0.25


TEMPLATE_WIDTH = 120
TEMPLATE_HEIGHT = 900

# These are deliberately broad first-pass positions. The evaluator reports
# peak positions and score distributions so the windows can be tightened after
# reviewing the current sample set.
TEMPLATES: Dict[StripType, TemplateConfig] = {
    "single": TemplateConfig(
        strip_type="single",
        width=TEMPLATE_WIDTH,
        height=TEMPLATE_HEIGHT,
        lines={
            "T": LineWindow(0.46, 0.035),
            "C": LineWindow(0.52, 0.035),
        },
    ),
    "dual": TemplateConfig(
        strip_type="dual",
        width=TEMPLATE_WIDTH,
        height=TEMPLATE_HEIGHT,
        lines={
            "T2": LineWindow(0.46, 0.030, peak_tolerance=0.016),
            "T1": LineWindow(0.52, 0.035, peak_tolerance=0.040),
            "C": LineWindow(0.60, 0.060),
        },
    ),
}


DEFAULT_THRESHOLDS = ThresholdConfig()
