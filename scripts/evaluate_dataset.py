#!/usr/bin/env python
from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter
from pathlib import Path
from typing import Iterable

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

import pandas as pd
from tqdm import tqdm

from strip_reader.config import DEFAULT_THRESHOLDS, ThresholdConfig
from strip_reader.labels import apply_label_overrides
from strip_reader.reader import analyze_image


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate the template reader on labels.csv.")
    parser.add_argument("labels_csv", help="Path to labels.csv.")
    parser.add_argument("--overrides", default="label_overrides.csv", help="Manual label overrides CSV.")
    parser.add_argument("--root", default=".", help="Project root for relative image paths.")
    parser.add_argument("--output-dir", default="outputs", help="Where to write evaluation artifacts.")
    parser.add_argument("--strip-type", choices=["all", "single", "dual"], default="all")
    parser.add_argument("--include-all", action="store_true", help="Evaluate rows excluded by default.")
    parser.add_argument("--debug-failures", action="store_true", help="Write debug images for failed rows.")
    parser.add_argument("--control-threshold", type=float, default=DEFAULT_THRESHOLDS.control)
    parser.add_argument("--target-threshold", type=float, default=DEFAULT_THRESHOLDS.target)
    args = parser.parse_args()

    labels = pd.read_csv(args.labels_csv, dtype=str).fillna("")
    labels = apply_label_overrides(labels, args.overrides)
    if args.strip_type != "all":
        labels = labels[labels["strip_type"] == args.strip_type].copy()
    if not args.include_all and "include" in labels:
        labels = labels[labels["include"] == "yes"].copy()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    thresholds = ThresholdConfig(control=args.control_threshold, target=args.target_threshold)

    result_rows: list[dict[str, object]] = []
    for row in tqdm(labels.to_dict(orient="records"), desc="Reading strips"):
        image_path = Path(args.root) / row["path"]
        debug_dir = output_dir / "debug_failures" if args.debug_failures else None
        result = analyze_image(
            image_path,
            strip_type=row["strip_type"] if row["strip_type"] in {"single", "dual"} else "auto",
            thresholds=thresholds,
            debug_dir=debug_dir,
        )
        flat = _flatten_result(row, result)
        flat["correct"] = _is_correct(row, result)
        result_rows.append(flat)

    results = pd.DataFrame(result_rows)
    results.to_csv(output_dir / "evaluation_results.csv", index=False, encoding="utf-8-sig")
    _write_summary(output_dir / "evaluation_summary.txt", results)
    _write_score_distribution(output_dir / "score_distribution.csv", results)
    print((output_dir / "evaluation_summary.txt").read_text(encoding="utf-8"))
    print(f"Wrote per-image results to {output_dir / 'evaluation_results.csv'}")
    return 0


def _flatten_result(label_row: dict[str, str], result: dict[str, object]) -> dict[str, object]:
    line_scores = _selected_line_scores(result)
    result_body = result.get("result", {})
    targets = result_body.get("targets", {}) if isinstance(result_body, dict) else {}
    summary = result.get("summary", {})
    flat: dict[str, object] = {
        "path": label_row["path"],
        "strip_type": label_row["strip_type"],
        "expected_validity": label_row.get("expected_validity", ""),
        "expected_single_result": label_row.get("expected_single_result", ""),
        "expected_t1": label_row.get("expected_t1", ""),
        "expected_t2": label_row.get("expected_t2", ""),
        "readability": result.get("readability"),
        "predicted_validity": result.get("validity"),
        "predicted_single_result": result_body.get("single") if isinstance(result_body, dict) else None,
        "predicted_t1": targets.get("T1"),
        "predicted_t2": targets.get("T2"),
        "confidence": result.get("confidence"),
        "reason": summary.get("reason") if isinstance(summary, dict) else None,
        "strip_count": summary.get("strip_count") if isinstance(summary, dict) else None,
    }
    for line_name in ("C", "T", "T1", "T2"):
        score = line_scores.get(line_name, {})
        flat[f"{line_name}_score"] = score.get("score")
        flat[f"{line_name}_peak_y_ratio"] = score.get("peak_y_ratio")
        flat[f"{line_name}_present"] = score.get("present")
    return flat


def _is_correct(label_row: dict[str, str], result: dict[str, object]) -> bool:
    if result.get("readability") != "ok":
        return False
    if label_row.get("expected_validity") == "valid" and result.get("validity") != "valid":
        return False
    result_body = result.get("result", {})
    if label_row.get("strip_type") == "single":
        expected = label_row.get("expected_single_result")
        predicted = result_body.get("single") if isinstance(result_body, dict) else None
        return expected in {"positive", "negative"} and predicted == expected
    if label_row.get("strip_type") == "dual":
        targets = result_body.get("targets", {}) if isinstance(result_body, dict) else {}
        return (
            label_row.get("expected_t1") in {"positive", "negative"}
            and label_row.get("expected_t2") in {"positive", "negative"}
            and targets.get("T1") == label_row.get("expected_t1")
            and targets.get("T2") == label_row.get("expected_t2")
        )
    return False


def _selected_line_scores(result: dict[str, object]) -> dict[str, object]:
    summary = result.get("summary", {})
    selected_index = summary.get("selected_strip_index") if isinstance(summary, dict) else None
    strips = result.get("strips", [])
    if not isinstance(strips, list):
        return {}
    if selected_index is None:
        return {}
    for strip in strips:
        if isinstance(strip, dict) and strip.get("index") == selected_index:
            scores = strip.get("line_scores", {})
            return scores if isinstance(scores, dict) else {}
    return {}


def _write_summary(path: Path, results: pd.DataFrame) -> None:
    lines: list[str] = []
    total = len(results)
    lines.append(f"rows_evaluated: {total}")
    if total == 0:
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return

    lines.append(f"readability_ok: {(results['readability'] == 'ok').sum()} / {total}")
    lines.append(f"overall_correct: {results['correct'].sum()} / {total}")
    lines.append("")
    lines.append("by_strip_type:")
    for strip_type, group in results.groupby("strip_type"):
        lines.append(f"  {strip_type}: {group['correct'].sum()} / {len(group)}")

    lines.append("")
    lines.append("reasons:")
    for reason, count in Counter(results["reason"]).most_common():
        lines.append(f"  {reason}: {count}")

    lines.append("")
    lines.append("confusion:")
    for name, expected_col, predicted_col in (
        ("single", "expected_single_result", "predicted_single_result"),
        ("T1", "expected_t1", "predicted_t1"),
        ("T2", "expected_t2", "predicted_t2"),
    ):
        lines.append(f"  {name}:")
        for key, count in _confusion_pairs(results, expected_col, predicted_col):
            lines.append(f"    {key}: {count}")

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _confusion_pairs(results: pd.DataFrame, expected_col: str, predicted_col: str) -> Iterable[tuple[str, int]]:
    counter: Counter[str] = Counter()
    for _, row in results.iterrows():
        expected = row.get(expected_col, "")
        if expected not in {"positive", "negative"}:
            continue
        predicted = row.get(predicted_col, "")
        counter[f"{expected}->{predicted}"] += 1
    return counter.most_common()


def _write_score_distribution(path: Path, results: pd.DataFrame) -> None:
    rows: list[dict[str, object]] = []
    for line_name, expected_col in (("T", "expected_single_result"), ("T1", "expected_t1"), ("T2", "expected_t2")):
        score_col = f"{line_name}_score"
        if score_col not in results:
            continue
        for label, group in results.groupby(expected_col):
            if label not in {"positive", "negative"}:
                continue
            scores = pd.to_numeric(group[score_col], errors="coerce").dropna()
            if len(scores) == 0:
                continue
            rows.append(
                {
                    "line": line_name,
                    "expected": label,
                    "count": len(scores),
                    "p10": scores.quantile(0.10),
                    "median": scores.quantile(0.50),
                    "p90": scores.quantile(0.90),
                }
            )
    with path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=["line", "expected", "count", "p10", "median", "p90"])
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    raise SystemExit(main())
