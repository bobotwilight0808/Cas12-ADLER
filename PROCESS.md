# Process Log

## 2026-06-08 - Bootstrap Template Reader Prototype

### Change

- Created Python/OpenCV prototype under `strip_reader/` with CLI scripts in `scripts/`.
- Added `.venv` dependency workflow, `requirements.txt`, tests, `labels.csv`, and README.
- Implemented strip localization, perspective normalization, fixed-line scoring, and dataset evaluation.

### Impact

- Default evaluable set from `labels.csv`: 71 images from 190 total images.
- Raw data remains in the working directory; code and generated labels are intended for git.

### Verification

- `python -m pytest -q`: passing.
- Latest full evaluation after manual-rule and 18-8 review:
  - readable/localized: 71 / 71
  - overall: 70 / 71
  - single-target: 4 / 4
  - dual-target: 66 / 67
  - output: `outputs/eval_final_18_8_review/evaluation_results.csv`

### Lessons

- The product manual uses a project-specific validity rule: a test is invalid only when all detection lines are absent.
- Dual-target physical order in the standardized orientation is `T2 / T1 / C`.
- `18-8` exposed an orientation failure: the strip needed a 180-degree correction even though a strong C line made the wrong direction look plausible.
- `18-2` is labeled T2-only in `labels.csv`, but visually can be accepted as double-positive; it is the only remaining default-set mismatch.

### Next Ideas

- Add a reviewed-label override file for ambiguous samples such as `18-2`.
- Keep T2 peak-position tolerance strict; T2 true positives are tightly clustered near the template center.
- Add all-lines-absent invalid samples before making strong claims about invalid detection.

## 2026-06-08 - Dataset Curation Workflow

### Change

- Added `label_overrides.csv` for manual review corrections without overwriting generated `labels.csv`.
- Added `scripts/prepare_dataset.py` to build `datasets/curated/` with normalized sample names and label folders.
- Updated evaluation to apply manual overrides by default when `label_overrides.csv` exists.

### Impact

- `18-2` is treated as double-positive according to manual review.
- The curated dataset can be regenerated from source folders, labels, and overrides.
- Raw images and curated symlink directories are still not committed; `datasets/` is an ignored local artifact.

### Verification

- `python -m pytest -q`: 7 passed.
- `python scripts/evaluate_dataset.py labels.csv --root . --output-dir outputs/eval_with_overrides`: 71 / 71.
- `python scripts/prepare_dataset.py --clean`: generated 190 organized samples.
- Included curated set:
  - dual double-positive: 26
  - dual negative: 15
  - dual T1-positive: 16
  - dual T2-positive: 10
  - single negative: 4
- Review set:
  - dual: 10
  - single: 109

### Lessons

- Keep raw acquisition folders immutable; build clean working datasets as generated artifacts.
- Separate automatic folder-label inference from human review corrections.

## 2026-06-08 - Single-Target Batch Labels And Split Evaluation

### Change

- Updated single-target label inference from the latest manual rules:
  - batch-1、batch-2: filenames containing `control` or `negative` are negative; all other samples are positive.
  - batch-3: `26` is positive; all other samples are negative.
  - batch-4: all samples are negative.
- Limited automatic label discovery to raw source folders `single-target/` and `dual-target/` so generated `datasets/` and `outputs/` images are not re-ingested.
- Added `--strip-type single|dual|all` to dataset evaluation so single-target and dual-target workflows can be tested separately.
- Added regression tests for the new single-target label rules and source-folder-only scanning.
- Normalized generated `labels.csv` line endings to LF to keep git diffs and whitespace checks clean.

### Impact

- `labels.csv` remains at 190 raw-source images.
- Curated dataset regeneration now places included samples as:
  - single positive: 27
  - single negative: 14
  - dual double-positive: 26
  - dual T1-positive: 16
  - dual T2-positive: 10
  - dual negative: 15
- Single-target default evaluation is now much broader than the earlier 4-sample smoke set.

### Verification

- `.venv/bin/python scripts/build_labels.py --root . --output labels.csv`: wrote 190 rows.
- `.venv/bin/python -m pytest -q`: 10 passed.
- `.venv/bin/python scripts/prepare_dataset.py --clean`: generated 190 organized samples.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type dual --output-dir outputs/eval_dual_labeled`: 67 / 67 correct.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type single --output-dir outputs/eval_single_labeled`: 26 / 41 correct, 40 / 41 readable.
- `git diff --check`: passed after normalizing `labels.csv` line endings.

### Lessons

- Dual-target remains stable after the label and evaluation changes.
- Single-target accuracy is limited by orientation/template ambiguity, not just threshold calibration.
- Fourth-batch negatives have a strong line at the current single-template `T` position; after 180-degree rotation that line lands near `C` and reads negative.
- Some first/second-batch positives show only a `C`-position line under the current orientation and overlap visually with negative controls, so image-only fixed-line reading cannot uniquely recover the manual label.

### Next Ideas

- Require a stable single-target shooting orientation or add an explicit single-target layout/orientation parameter before claiming single-target accuracy.
- Collect same-layout single-target validation data with known physical `T/C` positions.
- Keep dual-target and single-target metrics separate in reports.

## 2026-06-08 - Single-Target Arrow-Side Control Rule

### Change

- Clarified the physical single-target rule from the product manual and sample image:
  - single-target strips have two possible line positions.
  - the line closer to the arrow/sample-pad end is `C`.
  - the line farther from the arrow/sample-pad end is `T`.
- Updated README wording to describe single-target coordinates relative to the arrow end.
- Added detector debug fields describing whether the canonical crop was rotated to put the sample-pad/arrow end down.
- Added a regression test that keeps the single-target template `C` coordinate closer to the arrow end than `T`.

### Impact

- This confirms the current single-target template order is physically correct when the canonical image has arrows at the bottom: `T.y < C.y`.
- Single-target failures should now be interpreted as label/visibility conflicts or threshold misses, not as an inherent one-line ambiguity when the arrow end is visible.

### Verification

- Visual check on canonical single-target examples:
  - `single-target/batch-1/16-1.jpg`: arrow end is bottom; visible line is at the farther-from-arrow `T` position.
  - `single-target/batch-4/1.jpg`: arrow end is bottom; visible line is also at the farther-from-arrow `T` position.
- `.venv/bin/python -m pytest -q`: 11 passed.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type dual --output-dir outputs/eval_dual_arrow_rule`: 67 / 67 correct.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type single --output-dir outputs/eval_single_arrow_rule`: 26 / 41 correct, unchanged because this iteration clarified orientation/debug rather than relabeling fourth-batch samples.

### Lessons

- The earlier statement that a single visible line is inherently ambiguous was too broad. It is only ambiguous if the arrow/sample-pad end cannot be identified.
- The latest manual rule conflicts with the current automatic label rule "all batch-4 samples are negative" for fourth-batch images that visibly have a far-from-arrow `T` line.

## 2026-06-08 - Fourth-Batch Single-Target Review Overrides

### Change

- Added manual review overrides for all 8 `single-target/batch-4/` images.
- Each fourth-batch sample is marked `expected_single_result=positive` because the visible line is far from the arrow/sample-pad end, which corresponds to `T` by the manual rule.
- Kept the generated base label rule in `labels.csv` intact; `label_overrides.csv` now records the human review correction.

### Impact

- Effective curated included set now has:
  - single positive: 35
  - single negative: 6
  - dual double-positive: 26
  - dual T1-positive: 16
  - dual T2-positive: 10
  - dual negative: 15
- Single-target evaluation improves from 26 / 41 to 34 / 41.

### Verification

- `.venv/bin/python -m pytest -q`: 11 passed.
- `.venv/bin/python scripts/prepare_dataset.py --clean`: generated 190 organized samples with the updated single-target included counts.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type single --output-dir outputs/eval_single_fourth_review`: 34 / 41 correct, 40 / 41 readable.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type dual --output-dir outputs/eval_dual_fourth_review`: 67 / 67 correct.

### Lessons

- `label_overrides.csv` is the right place for visible-line review decisions that supersede folder/batch-derived labels.
- Remaining single-target misses are 6 weak/absent `T`-line positives and 1 slanted unreadable photo, not fourth-batch C/T mapping errors.

## 2026-06-08 - First/Second Batch Single-Target Negative Reviews

### Change

- Added manual negative overrides for:
  - `single-target/batch-1/16-9.jpg`
  - `single-target/batch-1/18-9.jpg`
  - `single-target/batch-2/16-4.jpg`
  - `single-target/batch-2/18-4.jpg`
- These samples were previously positive by the broad first/second-batch folder rule, but manual review says they are negative.

### Impact

- Effective curated included set now has:
  - single positive: 31
  - single negative: 10
  - dual double-positive: 26
  - dual T1-positive: 16
  - dual T2-positive: 10
  - dual negative: 15
- Single-target evaluation improves from 34 / 41 to 38 / 41.

### Verification

- `.venv/bin/python -m pytest -q`: 11 passed.
- `.venv/bin/python scripts/prepare_dataset.py --clean`: generated 190 organized samples with the updated single-target counts.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type single --output-dir outputs/eval_single_manual_negatives`: 38 / 41 correct, 40 / 41 readable.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type dual --output-dir outputs/eval_dual_manual_negatives`: 67 / 67 correct.

### Lessons

- The broad first/second-batch rule was intentionally useful for bootstrapping labels, but manual visible-line review is now the authority for edge cases.
- Remaining single-target misses are `single-target/batch-1/16-8.jpg`, `single-target/batch-2/18-3.jpg`, and unreadable `single-target/batch-1/16-6-angled.jpg`.

## 2026-06-09 - Multi-Strip Image Detection

### Change

- Added `detect_strips()` alongside the existing `detect_strip()` compatibility wrapper.
- Multi-strip photos are handled by:
  - building the existing saturated/printed-pixel mask,
  - grouping colored/printed components along the axis perpendicular to the likely strip axis,
  - running PCA/canonical crop on each component group independently.
- Updated `analyze_image()` to analyze each detected strip and return a `strips` array with per-strip details.
- Added top-level aggregation:
  - single-target: any readable strip positive means the image is positive.
  - dual-target: each target is positive if any readable strip is positive for that target.
- Added `strip_count` to evaluation output.
- Added a synthetic regression test for three parallel strips in one image.

### Impact

- `single-target/batch-1/16-6-angled.jpg` is now correctly handled as a 3-strip image instead of failing the single-strip shape check.
- Single-target readable count improves from 40 / 41 to 41 / 41.
- Single-target accuracy improves from 38 / 41 to 39 / 41.
- Dual-target accuracy remains 67 / 67.

### Verification

- `.venv/bin/python -m pytest -q`: 12 passed.
- `.venv/bin/python scripts/read_strip.py 'single-target/batch-1/16-6-angled.jpg' --strip-type single --debug-dir outputs/debug_single_16_6_multi`: detected `strip_count=3`, aggregate result positive.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type single --output-dir outputs/eval_single_multi_strip`: 39 / 41 correct, 41 / 41 readable.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type dual --output-dir outputs/eval_dual_multi_strip`: 67 / 67 correct.

### Lessons

- The previous failure reason for `16-6-angled` was not ordinary slant; the photo contains three strips, so global PCA over all colored pixels produced a wide cloud with a long/short ratio below the single-strip threshold.
- Connected components alone are not one-to-one with strips because each physical strip has separate blue text, arrows, and red line components. Components must be grouped into strip candidates before cropping.
- Current remaining single-target misses are only weak-positive visual cases: `single-target/batch-1/16-8.jpg` and `single-target/batch-2/18-3.jpg`.

## 2026-06-09 - Cleanup Around Current Multi-Strip Design

### Change

- Removed the old single-strip `detect_strip()` compatibility wrapper; `detect_strips()` is now the detector entrypoint.
- Simplified `analyze_image()` output:
  - top-level fields now describe only the image-level aggregate result.
  - per-strip line scores and detection details live only in `strips[]`.
  - old duplicate top-level `line_scores`, `debug`, `test_validity`, and `single_result` fields were removed from the reader output.
- Updated dataset evaluation to read the new schema and pull score-distribution values from the selected strip.
- Added reader-level tests for the cleaned schema and single-target any-positive aggregation.

### Impact

- CLI arguments and `strip_reader.analyze_image(...)` remain available.
- `read_strip.py` JSON output is intentionally breaking but cleaner.
- Current algorithm, thresholds, labels, and aggregation behavior are unchanged.

### Verification

- `.venv/bin/python -m pytest -q`: 14 passed.
- `.venv/bin/python scripts/read_strip.py 'single-target/batch-1/16-6-angled.jpg' --strip-type single`: detected 3 strips and returned aggregate positive in the new schema.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type single --output-dir outputs/eval_single_cleanup`: 39 / 41 correct, 41 / 41 readable.
- `.venv/bin/python scripts/evaluate_dataset.py labels.csv --root . --strip-type dual --output-dir outputs/eval_dual_cleanup`: 67 / 67 correct.

### Lessons

- Keeping image-level aggregate fields separate from per-strip detail makes the output easier to reason about and prevents stale compatibility fields from drifting.
- Evaluation should depend on the same public reader schema as `read_strip.py`, not on internal detector/analyzer fields.

## 2026-07-14 - Local Upload And Result Interface

### Change

- Added a Flask web entrypoint with a 15 MB upload limit, image-format verification, explicit single/dual target selection, and immediate temporary-file cleanup.
- Added a responsive local interface for click/drag upload, image preview, detected-strip overlays, aggregate results, confidence warnings, and per-strip line-score details.
- Added endpoint tests and documented the web startup workflow.

### Impact

- A single test-strip photo can now be analyzed without using the command line.
- The web layer calls the existing `analyze_image()` API, so template coordinates, thresholds, and aggregation rules remain unchanged.
- Uploaded images are not written to project data or output directories.

### Verification

- `source .venv/bin/activate && python -m pytest -q`: 18 passed.
- `python -m compileall -q strip_reader scripts`: passed.
- `git diff --check`: passed.
- HTTP upload smoke tests with `single-target/batch-1/16-1.jpg` and `dual-target/double-positive/8.jpg`: both readable and valid; results were single positive and T1/T2 double-positive respectively.
- Safari visual and interaction checks at desktop and narrow mobile-like widths: upload, preview, ROI overlay, error state, low-confidence notice, result summary, and line-score details rendered without overlap or horizontal overflow.

### Lessons

- The uploaded filename cannot reliably identify strip type, so the interface requires an explicit single-target or dual-target choice.
- Detection corner coordinates can be drawn over the original preview without changing the reader result schema.

## 2026-07-14 - Web Brand Favicon

### Change

- Added a local SVG favicon matching the interface brand mark and a static-resource regression assertion.

### Impact

- Browser tabs now use the product mark instead of a generic missing-page icon.

### Verification

- `source .venv/bin/activate && python -m pytest -q`: 18 passed, including the favicon static-resource assertion.
- `git diff --check`: passed.

### Lessons

- A self-contained local asset keeps the prototype polished without adding a network dependency.

## 2026-08-18 - Native Android App Foundation

### Change

- Reviewed all 8 slides in the client reference presentation and separated the mobile-app scope from the later paper-figure requests.
- Added a native Android project under `android-app/`, targeting Android 16 / API 36 with a minimum of Android 10 / API 29.
- Selected the official OpenCV 4.13.0 Android AAR for a fully offline reader and removed the prototype network permission, network policy, and HTTP client.
- Added the app theme, launcher metadata, brand palette, controls, and two-screen Android layout for camera, single-target, and dual-target flows.
- Extracted the supplied Cas/DNA icon directly from the presentation as the Android launcher and in-app brand asset; source and copied asset SHA-256 values match.
- Added Android equivalents of the calibrated template constants and NumPy-compatible percentile/statistics helpers as the first part of the local reader port.
- Ported saturated-pixel masking, connected-component strip grouping, covariance-axis estimation, perspective warp, sample-pad orientation, dual-target color orientation, line profiling, thresholding, confidence, and multi-strip aggregation to the OpenCV Android Java API.
- Reworked the Android activity to decode and analyze photos on a single background executor, with camera/gallery input, cancellation-safe UI generations, local error handling, original-photo overlays, and no server state.
- Added an interpreted-strip view that renders T/C or T2/T1/C positions from the aggregate result, including target-specific red/blue line colors and absent-line guides.
- Added JVM tests for NumPy-style statistics, line-window decisions, validity, confidence, and any-positive aggregation.
- Added an Android instrumentation parity test whose ignored build assets are generated from the locally available `labels.csv` rows and source photos; repository inspection corrected the evaluation-set count from 106 to 108 (41 single-target and 67 dual-target).
- Documented Android Studio and Gradle builds, APK location, offline behavior, and local dataset-test behavior; pinned the wrapper distribution to Gradle 9.4.1 for the API 36 / AGP 9.2 project.

### Impact

- The Android project is a self-contained reader that shares the Python algorithm's constants and behavior.
- Camera captures and selected images are decoded, classified, and rendered entirely on-device; the manifest has no Internet permission and the Flask interface remains an independent optional workflow.
- The generated debug APK is universal for direct testing, while the release AAB lets Android distribution split the bundled OpenCV native libraries by device architecture.

### Verification

- Initial AGP 9.2 configuration check rejected a lazy `Provider<Directory>` passed to the legacy Android SourceSet API; the generated instrumentation asset directory now uses a fixed path with an explicit merge-task dependency.
- The first Java compilation exposed an unavailable `Canvas.clipRoundRect` overload; the preview now clips through a rounded `Path`, which is supported across the selected API range.
- `assembleDebug` and JVM unit tests passed. The first instrumentation-package build then found that AGP's generated Android-test lint model also consumes the generated asset directory; that lint task now declares the same explicit preparation dependency as asset merging.
- AGP has multiple Android-test lint consumers for the generated dataset, so all Android-test tasks now share the explicit preparation dependency rather than naming individual merge/model tasks.
- Lint caught Android 16 predictive-back handling and URI flag typing. The activity now uses AndroidX `OnBackPressedDispatcher`, and persisted gallery access passes the explicit allowed read flag.
- Android 16 instrumentation completed all 108 images but exposed a real parity gap: the first port scored 27 / 41 single-target images correctly instead of 39 / 41. The test now emits per-image result and line-score diagnostics; the acceptance threshold remains unchanged.
- The diagnostics showed Android line scores matching Python (including the dual `18-2.jpg` scores) and accounted for every apparent mismatch: the test had omitted 13 manual review rows from `label_overrides.csv`. Generated test assets and expected labels now apply those overrides exactly like the Python evaluator.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug`: passed; debug APK created with the official OpenCV native libraries.
- `./gradlew :app:assembleDebugAndroidTest :app:lintDebug :app:bundleRelease`: passed; release AAB created and lint completed without errors.
- `./gradlew :app:connectedDebugAndroidTest` on Android 16 / API 36: all 108 included images passed the metric assertions (single 39 / 41, dual 67 / 67, all readable).
- The same connected dataset test on Android 10 / API 29 passed with identical metrics.
- Android 16 screenshots covered the home screen and a dual-positive result at 1080 x 2340, including the source-photo ROI, interpreted strip, low-confidence state, line details, and scrolling; Android 10 home-screen rendering at 1080 x 2280 also showed no clipping, overlap, or horizontal overflow.
- `python -m pytest -q`: 18 passed; `python -m compileall -q strip_reader scripts`: passed.
- Python dataset recheck remained 39 / 41 for single-target and 67 / 67 for dual-target, with every included image readable.
- APK manifest inspection showed no `android.permission.INTERNET`; `git diff --check` passed.
- Pending Android compilation, unit tests, dataset parity, and device UI verification.

### Lessons

- The supplied presentation asks for both real-time and uploaded-image detection, but the agreed first Android iteration uses camera capture rather than continuous frame streaming.
- Offline execution avoids server setup and image transfer, but requires classification parity checks because OpenCV and percentile implementations differ across Python and Java runtimes.
- Raw `labels.csv` expectations are not the evaluation source of truth by themselves; Android parity tests must apply `label_overrides.csv` before comparing results.
- OpenCV makes a universal debug APK large (about 151 MB); the release AAB is about 60 MB and is the appropriate distribution artifact.

## 2026-08-18 - PPT-Aligned Android Result Layout

### Change

- Rebuilt the Android analysis page around the layout requested on slide 4 of the client reference presentation:
  - the selected real photo and ROI overlay are shown on the left.
  - a dynamic single- or dual-target cassette illustration is shown on the right.
  - line scores, per-line thresholds, visibility states, confidence, and the final result are grouped below the two visuals.
- Replaced the abstract narrow-strip rendering with a cassette-shaped interpretation view containing a reading window, labeled T/T1/T2/C positions, and a sample well.
- Changed score presentation from approximate progress meters to three-decimal values and explicit thresholds.
- Added tone-specific final-result panels and removed the automatic result-page scroll so the real-photo/material comparison remains the first view.
- Shortened the result-page header to `SINGLE-TARGET` / `DUAL-TARGET` so it remains one line on narrow phones.

### Why

- The first Android result page used a vertical photo-first layout that did not match the client's supplied second-page structure.
- The client explicitly requires real material on the left, interpretation material on the right, and numerical detection output plus the result below.

### Expected Impact

- The second page now follows the client's visual hierarchy while preserving the existing offline analysis behavior and all calibrated detection constants.
- Numeric output is easier to compare with the PPT examples because full scores and thresholds are visible rather than represented primarily as meters.

### Verification

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: passed.
- `python -m pytest -q`: 18 passed.
- Installed the updated debug APK on the Android 16 emulator and analyzed the dual-positive sample successfully.
- Screenshot checks passed at the emulator's normal 1080 x 2340 / 440 dpi configuration and a narrow 720 x 1600 / 360 dpi configuration (320 dp wide): the side-by-side visuals, three-decimal scores, result panel, and action button showed without clipping or horizontal overflow.
- `git diff --check`: passed.

### Lessons

- On phone screens, preserving the PPT's left/right relationship requires compact visual labels and a stable-width numeric column.
- Long mode titles can wrap inside a fixed-height toolbar at 320 dp; shorter mode labels preserve the intended hierarchy without shrinking type dynamically.

## 2026-08-23 - Supplied ADLER Wordmark And Quieter Android Styling

### Change

- Added the supplied transparent wordmark as the Android UI asset `drawable-nodpi/adler_wordmark.png`; the source and packaged asset SHA-256 values both equal `2218d21590fb933d2e7feaec1730fc68b787e3bb407faf89f3cd49f58384d24e`.
- Replaced the manually typeset visible `ADLER` text on the home screen and result-page toolbar with the supplied wordmark image while preserving its original aspect ratio.
- Simplified the non-result palette to white, warm light gray, charcoal, and the blue already present in the supplied wordmark.
- Unified the camera, upload, and dual-target entry icons to charcoal; red and green remain reserved for detection meaning.
- Increased spacing between home actions, visual comparison sections, values, and the primary action button.
- Tightened the narrow result-page toolbar composition with a smaller wordmark, shorter divider margins, and a fixed gap before the retry button.

### Why

- The client requires the official supplied ADLER artwork instead of manually rendered brand letters.
- The preferred direction is a restrained Codex/GPT-like interface with fewer decorative colors and more breathing room.

### Expected Impact

- Brand rendering now matches the supplied artwork exactly on both Android screens.
- The interface reads more quietly and consistently without changing analysis behavior, result semantics, or offline operation.

### Verification

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: passed.
- `python -m pytest -q`: 18 passed.
- Installed and exercised the updated debug APK on an Android 16 emulator with the dual-positive sample.
- Normal 1080 x 2400 / 420 dpi screenshots verified the home wordmark, monochrome actions, result toolbar, side-by-side visuals, scores, and final result.
- Narrow 720 x 1600 / 360 dpi screenshots (320 dp wide) verified that the supplied toolbar wordmark, `DUAL-TARGET` label, and retry button retain visible separation with no clipping or horizontal overflow.
- `git diff --check`: passed.

### Lessons

- The supplied 404 x 230 transparent wordmark remains legible as a large home asset but needs a compact 52 x 30 dp treatment in a phone toolbar.
- A durable cached Android SDK is safer for later verification than relying on temporary platform and emulator files that macOS may clean between sessions.

## 2026-10-13 - Portable Android Builds Without Private Dataset Assets

### Change

- Made Android calibration-image packaging opt-in through `-PincludeCalibrationDataset=true`.
- Default build and lint tasks now generate an empty test-asset directory; they do not require local raw photos.
- The dataset instrumentation test reports a skipped test when calibration assets are absent. Explicit dataset builds still fail with an actionable message when an image is missing.

### Why

- The GitHub source repository excludes raw samples, so a fresh clone must build successfully without them.

### Expected Impact

- Normal APK builds, lint, and unit tests are portable. Dataset parity tests remain available to maintainers with the original samples.
- Detection code, template coordinates, and thresholds remain unchanged.

### Verification

- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`: passed with calibration packaging disabled.
- Explicit calibration preparation succeeded and packaged the 108 included photos plus both label CSV files. Switching the option off rebuilt the asset directory without photos.
- `python -m pytest -q`: 18 passed; Python syntax and unused-code lint passed; `git diff --check`: passed.
- A source-only checkout later passed all default Android builds/checks without raw photos; explicit dataset preparation in that checkout failed with the expected missing-image message. Device instrumentation was not rerun because the requested emulator session is stopped.

### Lessons

- Private calibration assets should be an explicit development option, not an implicit dependency of public build tasks.

## 2026-10-13 - Source Repository Hygiene And Developer Tooling

### Change

- Extended ignore rules for SDK/emulator directories, IDE files, credentials, signing keys, APK/AAB outputs, archives, and tool caches.
- Explicitly allowlisted the two supplied Android brand images as application resources.
- Split development tools into `requirements-dev.txt` and removed unused Matplotlib and scikit-learn runtime dependencies.
- Added a small `pyproject.toml` that centralizes Python test discovery and syntax/unused-code lint.

### Why

- Prepare a source-only repository that other developers can understand and use without local machine state or unnecessarily large dependencies.

### Expected Impact

- Runtime installation is smaller; development checks use one documented dependency file. Python tests remain grouped under `tests/` and Android tests remain in Gradle's standard directories.

### Verification

- Runtime import inspection confirmed no Matplotlib or scikit-learn usage in source, scripts, or tests.
- `python -m pytest -q`: 18 passed; Python bytecode compilation and Ruff checks passed.
- Ignore-rule checks covered emulator/SDK directories, APK outputs, local SDK settings, credentials, IDE state, and signing keys; `git diff --check`: passed.

### Lessons

- Platform conventions provide clearer separation than moving Android tests out of the directories Gradle expects.

## 2026-10-13 - Centralized UI Text And Explicit Reader Types

### Change

- Moved Android activity errors, mode labels, result metadata, line labels, thresholds, and visibility messages into `strings.xml`.
- Added the Android plural resource for strip counts while preserving English copy and US-locale numeric formatting.
- Replaced the Python reader's generic template annotation with `TemplateConfig`.
- Moved the synthetic-strip fixture factory from the production `AnalysisResult` class into `AnalysisResultTest`.

### Why

- Keep UI copy in Android resources and express the existing template contract directly in the Python entry point.

### Expected Impact

- Copy changes no longer require searching activity methods; production classes no longer contain test-only fixture generation. Detection behavior and all template/threshold values are unchanged.

### Verification

- Android compilation, debug APK build, lint, and all 6 JVM unit tests passed after the fixture move.
- `python -m pytest -q`: 18 passed; Ruff and `git diff --check`: passed.
- UI copy retains the previous English wording and US-locale three-decimal values. Device screenshots were not repeated because this cleanup does not change the layout and the emulator was stopped at the user's request.

### Lessons

- Preserve spacing and numeric locale when moving dynamic UI text into resources; Android string resources collapse unquoted whitespace.

## 2026-10-13 - Cas12-ADLER Repository Documentation

### Change

- Rewrote the root README around the Android app, Python reference implementation, directory responsibilities, build/install commands, offline behavior, result semantics, metrics, and limitations.
- Added `docs/TESTING.md` as the shared guide to Python tests, standard Android test directories, optional private dataset regression, and label tools.
- Replaced the original machine-specific Python setup path with portable commands and documented starting/stopping the independent Flask tool.
- Preserved the destination repository's existing MIT license and history through a non-destructive merge.

### Why

- Make the GitHub repository usable by a developer who has not seen the original local workspace or conversation.

### Expected Impact

- Build and usage instructions are discoverable from the repository home page; test code remains organized by platform without requiring private samples for standard checks.

### Verification

- A source-only checkout passed Python's 18 regression tests and Ruff checks.
- The same checkout passed all 6 Android JVM tests, debug lint, debug APK, instrumentation APK compilation, and release AAB builds without any raw photos or emulator.
- Documentation links resolve to existing files; public setup instructions have no machine-specific paths; `git diff --check`: passed.
- Audited tracked files and full Git history: no SDK/emulator state, raw photos, APK/AAB, virtual environment, private keys, or reference documents. The only committed raster images are the two Android brand assets.
- GitHub push permission was confirmed with a dry run. Final push follows this documentation commit.

### Lessons

- Document historical dataset metrics separately from fresh unit/build verification so readers can tell which checks require access to private data.

## 2026-10-13 - English-Only Project And Publication History

### Change

- Converted documentation, operating notes, license attribution, web UI, accessibility labels, API errors, test fixtures, CLI help, and label metadata to English.
- Normalized all 190 indexed sample paths and 13 review overrides to English names. Local hard links point to the same original image bytes; private originals remain unchanged.
- Updated label inference and source-directory discovery to the English folder conventions. Historical process references now use the current English sample aliases.
- Reused the supplied ADLER wordmark in the web header.
- At the user's explicit request, publication history will begin with a new English-only root commit. A recovery bundle is stored outside the project and is not pushed.

### Why

- Deliver an English-only project and remove the previously published mixed-language version from branch history.

### Expected Impact

- Code, documentation, UI, and tracked metadata use one language. Image pixels, line templates, thresholds, and classification rules are preserved.

### Verification

- Python unit tests: 18 passed; Ruff: passed.
- Dataset re-evaluation: 108/108 readable, single-target 39/41, dual-target 67/67.
- Complete tracked-text and filename audit: no Chinese text in source, documentation, UI, tests, or CSV metadata.
- All 190 regenerated labels match the normalized index and the original classification/review flags. Local aliases reference the same image files.
- Android JVM tests, lint, debug APK, device-test APK compilation, and release AAB build: passed. English paths packaged all 108 calibration photos and both CSV indexes when enabled.
- Playwright checks and screenshots passed at 1440 px, 360 px, and 320 px for positive and unreadable states: English text, loaded brand image, no text clipping, and no horizontal overflow. The temporary web server was stopped after verification.
- `git diff --check`: passed. Publication will use a parentless English-only root commit, followed by remote branch/tag verification.

### Lessons

- Language cleanup must cover structured dataset paths and inference conventions as well as visible text.
- A history rewrite requires explicit authorization and a separate local recovery copy.
