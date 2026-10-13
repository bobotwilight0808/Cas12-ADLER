#!/usr/bin/env python
from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import asdict
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from strip_reader.labels import infer_label, iter_image_paths


def main() -> int:
    parser = argparse.ArgumentParser(description="Build labels.csv from the current folder structure.")
    parser.add_argument("--root", default=".", help="Project root containing single-target/ and dual-target/.")
    parser.add_argument("--output", default="labels.csv", help="CSV file to write.")
    args = parser.parse_args()

    root = Path(args.root)
    rows = [infer_label(root, path) for path in iter_image_paths(root)]
    output = Path(args.output)
    with output.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(asdict(rows[0]).keys()), lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow(asdict(row))
    print(f"Wrote {len(rows)} rows to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
