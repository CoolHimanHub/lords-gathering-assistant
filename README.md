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

### V0.4.2 — Detection fusion and map-memory foundation

The pipeline now combines tile candidates with nearby OCR classifications and blue-march signals. A candidate can be resolved to a calibrated world coordinate through a caller-supplied resolver, while missing evidence remains unknown rather than being guessed. `MapMemory` now supports batch updates and stale-observation expiry so target selection does not rely indefinitely on old screen observations.

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

### V0.4.4 — MapMemory to target planning

The target layer now converts fused visual candidates into MapObservation records and provides TargetPlanner. Planning ignores candidates without calibrated K/X/Y, requires resource classification plus enabled type/level, rejects occupied or incoming-troop tiles, and applies a confidence gate before ranking. This layer produces overlay-ready target data only; it does not issue game taps or march commands.


## V0.4.5 — Live vision integration

The live capture path now connects the existing vision components:

`ScreenCaptureService → FrameAnalyzer → LiveMapScanner → VisionPipeline → CoordinateResolver → MapMemory → TargetPlanner → Overlay`

The scanner:
- runs visual template detection and blue-march signal detection on captured frames;
- resolves tile centers to calibrated world coordinates when calibration is usable;
- stores observations in stale-aware map memory;
- filters resources by the saved resource/level preferences and conservative confidence/occupancy rules;
- ranks targets only when an OCR coordinate is available as the current planning origin;
- draws up to 12 ranked target markers in a separate non-touchable overlay layer.

V0.4.5 remains **detection/planning only**. It does not tap the game, launch marches, change heroes, or alter gear. Automatic actions remain disabled by default.

### Important runtime note

Live coordinate resolution requires calibration samples collected from the same map/camera geometry. The current calibration is an affine approximation and is not yet a camera/zoom-invariant world model. Template detection also depends on the locally collected RESOURCE/MONSTER dataset; with no templates, the live pipeline will report zero visual tile candidates while OCR remains available.


## V0.4.7 — Gameplay-video calibration findings

Additional gameplay recordings were reviewed on 2026-09-19. They show several useful visual states that are now treated as first-class detection signals:

- Resource nodes display a small blue level badge; monster nodes display a red level badge.
- Selecting a resource opens a large translucent information panel containing the resource name/level, quantity, Occupier state (including Unoccupied), a Gather action, and a world coordinate such as K:355 X:167 Y:511.
- The top-center map HUD continuously exposes X and Y, which is useful for camera/world-coordinate tracking.
- Active march paths are visibly rendered as repeated orange directional arrows; the live scanner now detects orange as well as blue/cyan march evidence.
- The recordings contain substantial UI/chat overlays and large terrain features, reinforcing the need for spatial association and UI-region filtering rather than full-screen color matching alone.
- The same resource type appears at multiple levels and across different camera positions, so template matching remains supplemental to coordinate/OCR evidence rather than the sole source of truth.

The videos are used as visual calibration evidence; no gameplay recording is stored in the APK or repository.

### V0.4.7 focus

The next hardening pass should prioritize level-badge detection, temporal target tracking, popup-state validation, and camera/zoom consistency. Automatic taps and marches remain disabled.

## V0.4.8 — video-grounded state validation

Implemented from the supplied gameplay recordings:
- **Level badge detector:** supplements template matching with blue resource and red monster badge geometry, including map-area/UI-band filtering.
- **Popup state parser:** recognizes selected-node details such as resource/monster name, level, quantity, Occupier/Unoccupied, actions, and K/X/Y. Persistent HUD X/Y alone is not considered a popup.
- **Selected-node validation:** when a popup K/X/Y matches a resolved tile, popup state overrides weaker march/OCR guesses for occupancy, incoming troops, resource type, level, and quantity.
- **Temporal stabilization:** requires repeated consistent sightings for ordinary observations while allowing strong popup/occupied evidence through immediately, reducing one-frame false positives.
- **March evidence retained:** blue/cyan and orange path signals continue to mark nearby nodes as occupied/incoming unless an authoritative selected-node popup explicitly says the node is unoccupied.
- **Tests:** added popup parsing, popup occupancy override, and temporal tracking coverage.

The supplied recordings already provide the necessary examples for resource popups, occupied/in-motion resources, monster popups, dense maps, sparse terrain, and UI clutter; no additional recording is required for this V0.4.8 pass.


### V0.4.9 — build hardening

- Corrected Kotlin numeric type handling in the blue march confidence calculation.
- Hardened level-badge area filtering without relying on Kotlin range operator inference.
- Verified the orange march detector is present in the live vision package and wired into LiveMapScanner.
- Automatic gameplay actions remain disabled by default; detection and planning continue to require validation.


### V0.4.10 — state and calibration hardening

- **Occupancy state machine hardening:** a node previously observed occupied/incoming now requires repeated fresh free evidence before becoming eligible again.
- **Calibration geometry validation:** rejects one-dimensional/near-collinear world-coordinate samples that can yield unstable inverse transforms.
- Added regression tests for occupied-to-free transitions and unstable affine calibration sets.
- Planning remains detection-only; no automatic Gather/Hunt gesture is enabled by default.
