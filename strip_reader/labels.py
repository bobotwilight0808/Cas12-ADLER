from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import pandas as pd


IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}
SOURCE_DIRS = {"single-target", "dual-target"}


@dataclass(frozen=True)
class LabelRow:
    path: str
    strip_type: str
    batch: str
    folder: str
    expected_validity: str
    expected_single_result: str
    expected_t1: str
    expected_t2: str
    include: str
    is_suspect: str
    is_discarded: str
    is_hard_case: str
    notes: str


def iter_image_paths(root: str | Path) -> list[Path]:
    root_path = Path(root)
    paths: list[Path] = []
    for source_dir in SOURCE_DIRS:
        directory = root_path / source_dir
        if not directory.exists():
            continue
        paths.extend(path for path in directory.rglob("*") if path.suffix.lower() in IMAGE_SUFFIXES)
    return sorted(paths)


def infer_label(root: str | Path, image_path: str | Path) -> LabelRow:
    root_path = Path(root)
    path = Path(image_path)
    relative = path.relative_to(root_path)
    parts = relative.parts
    top = parts[0] if parts else ""
    batch = parts[1] if len(parts) > 1 else ""
    folder = "/".join(parts[:-1])
    text = "/".join(parts)

    strip_type = "single" if top == "single-target" else "dual" if top == "dual-target" else "unknown"
    is_discarded = _yes("discarded" in text or "unused" in text)
    is_suspect = _yes("suspect" in text)
    is_hard_case = _yes("angled" in text or "dark" in text or "suspect" in text)

    validity = "valid"
    single = "unknown"
    t1 = "unknown"
    t2 = "unknown"
    notes: list[str] = []

    if strip_type == "single":
        single = _infer_single_label(batch, path.name, text)
        if single == "unknown":
            notes.append("label_not_inferred_from_folder")
    elif strip_type == "dual":
        if "negative" in text:
            t1 = "negative"
            t2 = "negative"
        elif "double-positive" in text:
            t1 = "positive"
            t2 = "positive"
        elif "t1-positive" in text:
            t1 = "positive"
            t2 = "negative"
        elif "t2-positive" in text:
            t1 = "negative"
            t2 = "positive"
        else:
            notes.append("label_not_inferred_from_folder")
    else:
        validity = "unknown"
        notes.append("strip_type_not_inferred")

    has_known_result = single in {"positive", "negative"} or (
        t1 in {"positive", "negative"} and t2 in {"positive", "negative"}
    )
    include = _yes(has_known_result and is_discarded == "no" and is_suspect == "no")
    if is_discarded == "yes":
        notes.append("discarded_source_folder")
    if is_suspect == "yes":
        notes.append("suspect_sample_excluded_from_default_eval")

    return LabelRow(
        path=str(relative),
        strip_type=strip_type,
        batch=batch,
        folder=folder,
        expected_validity=validity,
        expected_single_result=single,
        expected_t1=t1,
        expected_t2=t2,
        include=include,
        is_suspect=is_suspect,
        is_discarded=is_discarded,
        is_hard_case=is_hard_case,
        notes=";".join(notes),
    )


def _yes(value: bool) -> str:
    return "yes" if value else "no"


def _infer_single_label(batch: str, filename: str, text: str) -> str:
    if batch in {"batch-1", "batch-2"}:
        if "control" in filename or "negative" in filename:
            return "negative"
        return "positive"
    if batch == "batch-3":
        return "positive" if Path(filename).stem == "26" else "negative"
    if batch == "batch-4":
        return "negative"
    if "negative" in text or "single-line" in text or "control" in text:
        return "negative"
    if "double-line" in text:
        return "positive"
    return "unknown"


def apply_label_overrides(labels: pd.DataFrame, overrides_path: str | Path) -> pd.DataFrame:
    path = Path(overrides_path)
    if not path.exists():
        return labels

    output = labels.copy()
    overrides = pd.read_csv(path, dtype=str).fillna("")
    columns = ["expected_single_result", "expected_t1", "expected_t2", "include"]
    for override in overrides.to_dict(orient="records"):
        source_path = override.get("path", "")
        if not source_path:
            continue
        mask = output["path"] == source_path
        if not mask.any():
            continue
        for column in columns:
            value = override.get(column, "")
            if value:
                output.loc[mask, column] = value
        note = override.get("notes", "")
        if note:
            existing = output.loc[mask, "notes"].astype(str)
            output.loc[mask, "notes"] = existing.apply(lambda text: f"{text};{note}" if text else note)
    return output
