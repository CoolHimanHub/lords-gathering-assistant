# Lords Mobile Companion

Native Android prototype for a compact, movable overlay companion.

## V0.2 progress

The project now has the first map-intelligence layer:

- Native Kotlin Android app
- Landscape-game friendly movable overlay
- Always-on-top permission flow
- MediaProjection screen capture foreground service
- ML Kit OCR pipeline
- OpenCV template matching
- Coordinate parser for K/X/Y and K:X:Y popup formats
- Game text classifier for resources and supported monsters
- Resource level and quantity extraction
- Occupancy/incoming-march keyword detection
- Rolling coordinate calibration model
- User resource/level preferences
- Target filtering
- Optional AccessibilityService gesture bridge
- Bundled monster lineup reference data
- GitHub Actions debug APK build

## Architecture

```text
MediaProjection
      |
      v
latest-frame throttling (2 FPS)
      |
      v
ML Kit OCR
      |
      +--> K/X/Y coordinate parser
      |
      +--> resource / monster classifier
      |
      +--> level / quantity / occupancy signals
      v
map observation
      |
      v
coordinate calibration + target selector
      |
      v
compact movable overlay
```

## Why this design

The supplied gameplay recordings show that K/X/Y values appear in the game UI and, more authoritatively, in tile/detail popups. Therefore the app should learn screen-to-world relationships from observed coordinates instead of relying on a guessed fixed pixel formula.

The scanner is intentionally throttled. It does not need to run heavyweight OCR on every 24 FPS game frame. The current prototype analyzes at most about two frames per second and skips a frame while OCR is still running.

## Build

Use Android Studio with JDK 17.

1. Clone this repository.
2. Open it in Android Studio.
3. Sync Gradle.
4. Build the `app` module.
5. Install on Android 8.0+.
6. Grant **Display over other apps**.
7. Start the scanner and grant Android screen-capture permission.
8. Enable the AccessibilityService only if gesture automation is required.

GitHub Actions also builds a debug APK on pushes to `main` and uploads it as a workflow artifact.

## Next stage: V0.3

The next implementation should use the supplied recordings to create actual learned templates and detectors:

1. Extract representative map tiles from the recordings.
2. Label resource icons by type and level.
3. Label monster icons by name and level.
4. Detect the blue numeric level badge independently from the icon.
5. Detect troop/march arrows as a separate occupancy signal.
6. Detect and OCR tile/detail popups for authoritative K/X/Y.
7. Maintain a local map cache keyed by `K,X,Y`.
8. Add camera-pan/zoom tracking so the map cache survives viewport movement.
9. Add target ranking by preference, level, remaining quantity and distance.
10. Add explicit GATHERING/HUNTING gear-state checks before any action.
11. Keep automatic gestures disabled until popup validation succeeds.

## Automation safety

Automatic actions are disabled by default. A visual candidate should never be enough by itself to trigger a tap. The intended pipeline is:

`detect -> validate popup -> verify state -> check preferences -> optional gesture`

Game UI, rules and third-party terms can change, so validate the current game version before enabling automation.