# Development and Testing Guide

Commands start at the repository root unless stated otherwise. Python and Android tests follow their respective toolchain conventions and remain separate from production code.

## Test Layout

| Location | Coverage | Raw photos | Android device |
| --- | --- | --- | --- |
| `tests/` | Python scoring, orientation, localization, labels, aggregation, web API | Not required | Not required |
| `android-app/app/src/test/` | Java statistics, thresholds, validity, aggregation | Not required | Not required |
| `android-app/app/src/androidTest/` | On-device OpenCV dataset regression | Optional local dataset | Required |

Android uses the standard Gradle `src/test/` and `src/androidTest/` directories. Synthetic fixture helpers live in their test classes.

## Standard Checks

### Python

```bash
source .venv/bin/activate
python -m pip install -r requirements-dev.txt
python -m pytest -q
ruff check strip_reader scripts tests
```

`pyproject.toml` limits test discovery to `tests/`. Ruff checks syntax, unused variables, and imports. The current regression suite contains 18 tests using synthetic images and temporary files.

### Android

Configure JDK 17, Android SDK Platform 36, and Build Tools 36.0.0, then run:

```bash
cd android-app
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The JVM suite contains 6 tests. These checks require no device, emulator, or raw dataset.

Compile the device test APK with:

```bash
./gradlew :app:assembleDebugAndroidTest
```

Standard builds do not package raw photos. Without dataset assets, `OfflineDatasetParityTest` is reported as skipped rather than claiming that dataset metrics were verified.

## Local Dataset Regression

The repository contains label indexes, not photos. Maintainers with the original data should populate `single-target/` and `dual-target/` at the repository root to match the English relative paths in `labels.csv`.

Rows with `include=yes` enter default evaluation. `label_overrides.csv` contains manual review corrections. The included dataset has 41 single-target and 67 dual-target photos.

### Python Evaluation

```bash
python scripts/evaluate_dataset.py labels.csv --root . --output-dir outputs/evaluation
python scripts/evaluate_dataset.py labels.csv --root . --strip-type single --output-dir outputs/eval_single
python scripts/evaluate_dataset.py labels.csv --root . --strip-type dual --output-dir outputs/eval_dual
```

Outputs include per-image results, score distributions, and a summary. Add `--debug-failures` to save failed-image overlays and normalized strips.

### Android Device Evaluation

Connect an Android 10 or newer test device and authorize USB debugging:

```bash
cd android-app
./gradlew :app:connectedDebugAndroidTest -PincludeCalibrationDataset=true
```

The option temporarily copies included photos into `app/build/generated/androidTestAssets/` for the test APK only. Missing files produce an explicit build error. Tests check readability and the baseline classifications: single-target `39/41`, dual-target `67/67`.

Regenerate assets after changing local photos:

```bash
./gradlew :app:prepareCalibrationAssets -PincludeCalibrationDataset=true --rerun-tasks
```

Changes to thresholds, template coordinates, or interpretation rules require Python dataset evaluation and Android device regression. Record affected samples, before/after results, and metrics in `PROCESS.md`.

## Labels and Dataset Organization

These maintainer commands generate labels or organize local files:

```bash
python scripts/build_labels.py --root . --output labels.csv
python scripts/prepare_dataset.py --mode symlink
```

The first command regenerates the label index; use it only with complete source folders. Manual corrections remain in `label_overrides.csv`. The second creates relative symlinks under `datasets/curated/`, grouped by type and result. It also supports `--mode copy` and `--mode hardlink`.

Folder conventions use `batch-1` through `batch-4` for single-target batches and `t1-positive`, `t2-positive`, `double-positive`, or `negative` for dual-target groups. Review flags use `discarded`, `unused`, `suspect`, `angled`, and `dark` in paths.

## Contribution Rules

- Commit source, required resources, test code, label CSVs, and documentation.
- Keep raw photos, SDKs/emulators, build files, virtual environments, signing keys, APKs, and AABs local.
- Use English for code, comments, documentation, UI text, labels, and filenames.
- Update `PROCESS.md` with reasons, expected impact, verification, and new risks.
- Run relevant checks and `git diff --check` before committing each completed change.
