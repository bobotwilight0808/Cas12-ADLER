#!/usr/bin/env python
from __future__ import annotations

import argparse
import csv
import os
import shutil
import sys
from pathlib import Path

import pandas as pd

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from strip_reader.labels import apply_label_overrides


def main() -> int:
    parser = argparse.ArgumentParser(description="Create an organized curated dataset directory.")
    parser.add_argument("--labels", default="labels.csv", help="Source labels CSV.")
    parser.add_argument("--overrides", default="label_overrides.csv", help="Manual label overrides CSV.")
    parser.add_argument("--root", default=".", help="Project root containing source images.")
    parser.add_argument("--output", default="datasets/curated", help="Output curated dataset directory.")
    parser.add_argument("--mode", choices=["symlink", "copy", "hardlink"], default="symlink")
    parser.add_argument("--clean", action="store_true", help="Remove output directory before rebuilding.")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    output = Path(args.output)
    if args.clean and output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)

    labels = pd.read_csv(args.labels, dtype=str).fillna("")
    labels = apply_label_overrides(labels, args.overrides)

    counters: dict[str, int] = {}
    manifest_rows: list[dict[str, str]] = []
    for row in labels.to_dict(orient="records"):
        source = root / row["path"]
        label = _label_for_row(row)
        subset = "included" if row.get("include") == "yes" and label != "unknown" else "review"
        directory = output / row["strip_type"] / subset / label
        directory.mkdir(parents=True, exist_ok=True)

        key = f"{row['strip_type']}_{subset}_{label}"
        counters[key] = counters.get(key, 0) + 1
        destination = directory / f"{key}_{counters[key]:04d}{source.suffix.lower() or '.jpg'}"
        _link_or_copy(source, destination, args.mode)

        manifest_rows.append(
            {
                "curated_path": str(destination),
                "source_path": row["path"],
                "strip_type": row["strip_type"],
                "subset": subset,
                "label": label,
                "include": row.get("include", ""),
                "batch": row.get("batch", ""),
                "is_suspect": row.get("is_suspect", ""),
                "is_discarded": row.get("is_discarded", ""),
                "notes": row.get("notes", ""),
            }
        )

    manifest = output / "manifest.csv"
    with manifest.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(manifest_rows[0].keys()))
        writer.writeheader()
        writer.writerows(manifest_rows)

    print(f"Wrote {len(manifest_rows)} organized samples to {output}")
    print(f"Wrote manifest to {manifest}")
    for key, count in sorted(counters.items()):
        print(f"{key}: {count}")
    return 0


def _label_for_row(row: dict[str, str]) -> str:
    if row["strip_type"] == "single":
        result = row.get("expected_single_result", "unknown")
        return result if result in {"positive", "negative"} else "unknown"
    if row["strip_type"] == "dual":
        t1 = row.get("expected_t1", "unknown")
        t2 = row.get("expected_t2", "unknown")
        if t1 == "negative" and t2 == "negative":
            return "negative"
        if t1 == "positive" and t2 == "negative":
            return "t1_positive"
        if t1 == "negative" and t2 == "positive":
            return "t2_positive"
        if t1 == "positive" and t2 == "positive":
            return "double_positive"
    return "unknown"


def _link_or_copy(source: Path, destination: Path, mode: str) -> None:
    if destination.exists() or destination.is_symlink():
        destination.unlink()
    if mode == "copy":
        shutil.copy2(source, destination)
    elif mode == "hardlink":
        os.link(source, destination)
    else:
        relative_source = os.path.relpath(source, destination.parent)
        os.symlink(relative_source, destination)


if __name__ == "__main__":
    raise SystemExit(main())
