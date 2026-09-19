# Lords Mobile Companion

Native Android prototype for a compact, movable overlay companion.

## v0.1

- Native Kotlin Android application
- Landscape-game friendly movable overlay
- Always-on-top permission flow
- MediaProjection screen-capture foreground service
- ML Kit OCR dependency ready
- OpenCV template matching engine
- Optional AccessibilityService gesture bridge
- Resource and monster data models
- Local preferences
- Bundled monster lineup reference data

## Build

Use Android Studio with JDK 17.

1. Clone the repository.
2. Open it in Android Studio.
3. Sync Gradle.
4. Build the app module.
5. Install on Android 8.0+.
6. Grant Display over other apps.
7. Enable the LM Companion accessibility service only when gesture automation is wanted.
8. Start the scanner and grant Android screen-capture permission.

## Architecture

MediaProjection -> frame processing -> OpenCV candidate detection -> ML Kit OCR -> coordinate/target parser -> preference engine -> overlay.

Automatic game actions are disabled by default. The next stage adds popup verification, occupancy detection, coordinate calibration, template learning, target ranking, searchable lineups and controlled gesture actions.

## Disclaimer

Game UI and rules can change. Verify the current game version and applicable account/game rules before enabling automated actions.
