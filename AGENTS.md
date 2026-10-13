# Agent Operating Notes

This project is a template-based test-strip reader prototype. Keep iterations small, measurable, and committed.

## Workflow

- Read `PROCESS.md` before making changes so the latest assumptions, metrics, and lessons are carried forward.
- After each code/config/documentation change, update `PROCESS.md` with:
  - what changed
  - why it changed
  - expected impact
  - verification result
  - any lesson or new risk
- Run relevant verification before committing:
  - `source .venv/bin/activate`
  - `python -m pytest -q`
  - when classification logic changes: `python scripts/evaluate_dataset.py labels.csv --root . --output-dir outputs/<name>`
- Commit every completed modification as its own git commit. Keep commit messages specific and outcome-focused.

## Repository Hygiene

- Do not commit `.venv/`, `outputs/`, raw images, PDFs, PPTX, or videos unless explicitly requested.
- Commit source code, tests, requirements, `labels.csv`, and project documentation.
- Prefer small calibration changes over broad rewrites; record metric changes in `PROCESS.md`.
- When changing thresholds or template coordinates, include the affected sample paths and before/after result.

## Current Model Shape

- Localization: saturated printed/line pixels -> PCA strip axis -> perspective warp to `120 x 900`.
- Orientation: dual-target strips compare both orientations using target-line color template scores.
- Line detection: fixed template windows with local-background-subtracted line scores.
- Interpretation: per manual, only all lines absent is invalid; otherwise T/T1/T2 decide result.
- Use English for source code, comments, documentation, UI text, labels, and filenames.
