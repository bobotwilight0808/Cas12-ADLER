# Cas12-ADLER

**ADLER Strip Reader** is a template-based image reader for single-target and dual-target lateral-flow test strips. The repository includes a native Android app, a Python reference implementation, and a local web tool. OpenCV handles strip localization, orientation, perspective correction, and line scoring.

The Android app processes photos on the device. It supports camera capture and gallery selection without a computer or analysis server. The current prototype is calibrated for the strip layouts used in this project.

## Features

- Single-target `T / C` and dual-target `T2 / T1 / C` interpretation.
- Original-photo detection overlays and an interpreted-strip illustration.
- Per-line scores, thresholds, visibility, confidence, and final results.
- Unreadable-image, no-line, low-confidence, negative, and positive states.
- Independent analysis and aggregation of multiple parallel strips in one photo.
- Background Android processing, photo orientation handling, and large-image sampling.

The first version analyzes a captured photo. Select the single-target or dual-target mode before analysis.

## Repository Layout

```text
Cas12-ADLER/
|-- android-app/           # Native Java Android app and Gradle Wrapper
|   `-- app/src/
|       |-- main/          # UI, resources, and offline reader
|       |-- test/          # JVM unit tests
|       `-- androidTest/   # Device dataset regression tests
|-- strip_reader/          # Python reader and Flask API
|-- scripts/               # CLI, web, labels, and dataset tools
|-- tests/                 # Python unit and web API tests
|-- static/                # Web styles, scripts, and brand resources
|-- templates/             # Flask HTML templates
|-- docs/                  # Development and testing guide
|-- labels.csv             # Sample index; photos are stored separately
|-- label_overrides.csv    # Manual review corrections
|-- requirements.txt       # Python runtime dependencies
|-- requirements-dev.txt   # Python development dependencies
|-- pyproject.toml         # Python test and lint configuration
`-- PROCESS.md             # Changes, verification, and calibration notes
```

Modules have separate responsibilities: `detector` localizes strips, `orientation` and `StripAnalyzer` resolve direction, `analyzer` scores lines, `reader` aggregates results, and `config` holds templates and thresholds. UI code calls the reader rather than implementing classification.

The repository includes the required ADLER brand resources. SDKs, emulators, virtual environments, raw photos, reference presentations, signing keys, APKs, and AABs are excluded by `.gitignore`.

## Android App

### Requirements

| Setting | Value |
| --- | --- |
| App name | ADLER Strip Reader |
| Application ID | `com.adler.stripreader` |
| Version | `0.1.0` |
| Minimum OS | Android 10 / API 29 |
| Compile and target SDK | Android 16 / API 36 |
| Java | 17 |
| Android Gradle Plugin | 9.2.0 |
| Gradle Wrapper | 9.4.1 |
| OpenCV Android | Official AAR 4.13.0 |

### Open and Build

```bash
git clone https://github.com/bobotwilight0808/Cas12-ADLER.git
cd Cas12-ADLER
```

Open `android-app/` in Android Studio. Install Android SDK Platform 36 and Build Tools 36.0.0 through SDK Manager, select JDK 17, and sync Gradle. The first build needs a network connection to download dependencies. Detection in the installed app runs offline.

With `JAVA_HOME` and `ANDROID_HOME` configured, build from a terminal:

```bash
cd android-app
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat :app:assembleDebug`. Android Studio creates the local SDK configuration in `local.properties`; this file is not committed. Standard builds do not require sample photos.

The installable debug APK is generated at:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

The universal APK includes OpenCV libraries for several CPU architectures and is approximately 151 MB. Transfer it to an Android 10 or newer phone, allow installation from the chosen file source, and open it. For an authorized USB debugging device, install from the repository root:

```bash
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

### Use the App

1. Choose camera capture, single-target image, or dual-target image.
2. Camera capture asks for the strip type before opening the system camera. Image entries open the system picker.
3. The result page shows the real photo and detection overlay on the left, an interpreted strip on the right, and scores and results below.
4. Retry the analysis or choose another photo.

The Android app has no `INTERNET` permission. It uses local Java/OpenCV analysis and does not call the Flask API.

### Release Bundle

```bash
cd android-app
./gradlew :app:bundleRelease
```

The AAB is generated at `android-app/app/build/outputs/bundle/release/app-release.aab`. Distribution can split native libraries by device architecture. Configure your own production signing key before release; the repository contains no signing credentials.

## Python and Local Web Tool

### Install

Python 3.10 or newer is required. Run from the repository root:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
```

On Windows PowerShell, activate with `.venv\Scripts\Activate.ps1`.

### Analyze One Image

```bash
python scripts/read_strip.py path/to/strip.jpg --strip-type single
python scripts/read_strip.py path/to/strip.jpg --strip-type dual --debug-dir outputs/debug_one
```

`--debug-dir` saves detection overlays and normalized strip images. Automatic type selection uses `single` or `dual` in the image path. Specify the type explicitly for arbitrary paths.

The Python API is also available:

```python
from strip_reader import analyze_image

result = analyze_image("path/to/strip.jpg", strip_type="dual")
```

Results contain `readability`, `strip_type`, `validity`, `result`, `confidence`, `summary`, and individual `strips`. Each strip includes line scores, thresholds, ROI corners, and orientation details.

### Start and Stop the Web Tool

```bash
python scripts/run_web.py
```

Open <http://127.0.0.1:5000>, select the strip type, and upload a JPG, PNG, or WebP image. Processing runs on the computer, with a 15 MB upload limit. Temporary uploads are deleted after analysis.

Press `Ctrl+C` in the server terminal to stop it. If the port is occupied, run `PORT=5001 python scripts/run_web.py` and use that port. The Android app and web tool run independently.

## Interpretation and Baseline

Pipeline: color mask -> connected-component grouping -> PCA axis estimation -> perspective correction to `120 x 900` -> orientation selection -> template-window scoring -> multi-strip aggregation.

Current project rules:

- Single-target strips use `T / C`; dual-target strips use `T2 / T1 / C`.
- Local-background-subtracted scores determine visibility. Target and control thresholds are `6.0` and `1.0`; some target windows also check peak position.
- All lines absent means `invalid_no_lines`. Under this project's interpretation rule, a visible target line produces a positive result even when C is absent.
- Any positive strip makes that target positive in a multi-strip image. Individual readings are retained.
- `high` and `low` confidence are threshold-distance indicators.

Baseline on the 108 included local photos after manual label overrides:

| Type | Included | Correct | Readable |
| --- | ---: | ---: | ---: |
| Single-target | 41 | 39 / 41 | 41 / 41 |
| Dual-target | 67 | 67 / 67 | 67 / 67 |

Previous Android 10 and Android 16 device tests reached the same baseline. The two single-target misses are weak positives below the T threshold. The current dataset lacks all-lines-absent invalid samples. New strip layouts, reagents, or capture conditions require evaluation and calibration.

## Development and Testing

```bash
python -m pip install -r requirements-dev.txt
python -m pytest -q
ruff check strip_reader scripts tests
```

Python tests use synthetic images and temporary files. Android JVM tests are in `src/test/`; device tests are in `src/androidTest/`. Raw dataset directories use English names: `single-target/` and `dual-target/`.

See the [testing and dataset guide](docs/TESTING.md) for Android checks, optional dataset regression, and label tools. [PROCESS.md](PROCESS.md) records changes, verification, and known limitations.

## License

The project uses the repository's [MIT License](LICENSE). Third-party dependencies and brand resources retain their respective ownership.
