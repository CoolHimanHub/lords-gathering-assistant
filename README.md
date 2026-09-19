# Lords Mobile Companion

Native Android prototype for a compact, movable overlay companion.

## Current progress

### V0.3.1 — Visual calibration / training console

The app now includes a local visual dataset workflow:

- Load real game screenshots from device storage.
- Draw a selection rectangle around a visual target.
- Label RESOURCE, MONSTER, LEVEL, COORDINATE, MARCH or CASTLE.
- Store cropped PNG samples and JSONL metadata in app-private storage.
- Record K/X/Y against the selected tile center for map calibration.
- Persist up to 32 coordinate samples.
- Fit an affine screen↔world calibration and display its RMS error.
- Require 3+ coordinate samples before calibration becomes usable.

### V0.3.2 foundation

The persistent CalibrationStore now feeds the existing affine calibrator, so calibration data survives app restarts. The next detector layer can consume this calibration instead of assuming a fixed isometric pixel formula.

### V0.4.1 — Template-based tile detection foundation

The visual detection layer now supports:

- TileTemplate / DetectedTile / DetectionFrame models for resource and monster candidates.
- Loading RESOURCE and MONSTER crops collected by the calibration/training console.
- OpenCV template matching with a configurable confidence threshold.
- Non-maximum suppression to avoid duplicate overlapping candidates.
- Per-candidate center coordinates for later map-coordinate association.
- Unit coverage for detection-model separation and geometry.

This is intentionally a candidate detector, not an automatic-action engine. The next layer will associate level/OCR text, occupancy/march signals, and calibrated K/X/Y coordinates before any target can be considered actionable.

## Architecture

```text
MediaProjection
      |
      v
latest-frame throttling
      |
      +--> ML Kit OCR
      +--> OpenCV CV
      |
      v
map observations / training samples
      |
      +--> persistent K/X/Y calibration
      |
      +--> resource / monster / march detectors
      v
world-coordinate map memory
      |
      v
target selector / ranker
      |
      v
compact movable overlay
```

## Build

Use Android Studio with JDK 17.

1. Clone this repository.
2. Open it in Android Studio.
3. Sync Gradle.
4. Build the app module.
5. Install on Android 8.0+.
6. Grant Display over other apps.
7. Start the scanner and grant Android screen-capture permission.
8. Enable the AccessibilityService only if gesture automation is required.

GitHub Actions is configured to build a debug APK on pushes to main and pull requests.

## Calibration workflow

For a good affine calibration:

1. Load a screenshot showing a clear map tile.
2. Select the tile/icon center.
3. Choose COORDINATE.
4. Enter the exact K/X/Y shown by the game for that tile.
5. Repeat for at least three non-collinear points.
6. Keep samples from the same camera zoom/pan state when possible.
7. Watch the RMS error in the calibration console.
8. Recalibrate after a significant camera zoom change if the fitted error increases.

The coordinate selection point is deliberately treated as a tile-center observation. A K/X/Y text popup's screen location is not itself a valid tile-center calibration point.

## Automation safety

Automatic actions are disabled by default. A visual candidate should never be enough by itself to trigger a tap. The intended pipeline is:

`detect -> validate popup -> verify state -> check preferences -> optional gesture`

Game UI, rules and third-party terms can change, so validate the current game version before enabling automation.