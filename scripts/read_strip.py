#!/usr/bin/env python
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from strip_reader.config import DEFAULT_THRESHOLDS, ThresholdConfig
from strip_reader.reader import analyze_image


def main() -> int:
    parser = argparse.ArgumentParser(description="Read one lateral-flow strip image.")
    parser.add_argument("image", help="Path to a strip photo.")
    parser.add_argument("--strip-type", choices=["auto", "single", "dual"], default="auto")
    parser.add_argument("--control-threshold", type=float, default=DEFAULT_THRESHOLDS.control)
    parser.add_argument("--target-threshold", type=float, default=DEFAULT_THRESHOLDS.target)
    parser.add_argument("--debug-dir", default=None, help="Directory for overlay/canonical debug images.")
    args = parser.parse_args()

    result = analyze_image(
        args.image,
        strip_type=args.strip_type,
        thresholds=ThresholdConfig(control=args.control_threshold, target=args.target_threshold),
        debug_dir=args.debug_dir,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
