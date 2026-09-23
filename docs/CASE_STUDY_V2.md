# LM Companion V2.0.0 — Deep Case Study

## 1. Product objective

LM Companion is an Android-only companion overlay for Lords Mobile. The original specification called for a lightweight, movable, dark overlay that can observe the world map, identify resource and monster tiles, expose K/X/Y coordinates, rank targets according to user preferences, provide monster-hunt lineup reference data, and eventually perform guarded gather/hunt interactions.

The implementation deliberately separates **perception**, **world modelling**, **decision support**, and **interaction**. This prevents a noisy computer-vision result from becoming an unsafe tap.

## 2. Why Android-native was selected

The original prompt allowed Python/Tkinter/PySide or Node/Electron, but the target is an Android phone running the game in landscape. A native Kotlin application is the practical architecture because Android provides:
- MediaProjection for user-consented screen capture.
- Foreground services for sustained capture.
- Application overlays through TYPE_APPLICATION_OVERLAY.
- AccessibilityService gesture dispatch for controlled interaction.

Android 14+ requires a declared mediaProjection foreground-service type and fresh user consent for each capture session. The app follows that model instead of caching or reusing a projection grant.

## 3. Runtime architecture

Screen
  -> MediaProjection
  -> ImageReader
  -> bitmap conversion
  -> OCR / OpenCV / badge detection
  -> detection fusion
  -> coordinate resolver
  -> temporal stabilization
  -> map memory
  -> discovery ranking
  -> preference ranking
  -> target validation
  -> action candidate
  -> guarded scheduler
  -> Accessibility gesture

The overlay is deliberately not the source of truth. The world model and current-frame evidence are.

## 4. Capture subsystem

The capture service owns:
- MediaProjection lifecycle
- VirtualDisplay
- ImageReader
- background frame processing
- watchdogs
- latency and memory diagnostics
- explicit test timers
- session persistence

The device tests supplied during development demonstrated sustained sessions with healthy capture state, but also a high input-frame drop rate caused by deliberate backpressure. The scanner processes a small accepted subset rather than attempting to OCR every display frame.

This is a correct throughput trade-off for a CPU-constrained phone: the objective is useful accepted frames, not maximum raw FPS.

## 5. Perception subsystem

### OCR
ML Kit Text Recognition extracts:
- K/X/Y
- level labels
- resource names
- monster names
- quantity values
- occupancy words
- action vocabulary

### OpenCV
OpenCV is used for:
- level badge segmentation
- contour/geometry filtering
- resource/monster badge classification
- native runtime health checks

### Template learning
The calibration console stores user-labelled crops locally. These become templates for subsequent scans. This is intentionally device-local: game-specific visual assets can change with resolution, UI scale and game updates.

## 6. World coordinates

The app maintains an affine map between screen pixels and kingdom coordinates. Coordinate samples can be collected through the calibration console. Multiple non-collinear samples are required before the model is considered reliable.

K/X/Y shown by the game's map HUD is treated as a high-value coordinate anchor.

## 7. Camera continuity

A major engineering issue discovered during real-device testing was calibration drift.

The same visible tile could temporarily receive slightly different calculated K/X/Y coordinates. If coordinate identity were the only tracking key, a stationary map would appear to contain new objects every frame.

V2 therefore uses:
1. exact world-coordinate identity when reliable;
2. semantic identity + nearest screen position as fallback;
3. temporal expiry;
4. camera motion statistics.

Camera stability remains a hard prerequisite for automated interaction, but discovery is no longer discarded merely because the camera is still settling.

This distinction is critical:
- discovery may continue under uncertainty;
- action authority may not.

## 8. Resource model

Supported resource classes:
- Food
- Stone
- Wood
- Ore
- Gold
- Gems
- Energon

Each observed resource can carry:
- K/X/Y
- level 1–5
- quantity when available
- occupied state
- incoming march state
- screen location
- confidence
- evidence flags
- timestamp

Exact quantity is intentionally obtained from detailed tile information/OCR when available rather than being inferred from the small map icon.

## 9. Occupancy and march handling

Nearby march signals are associated with candidate tiles using distance and ambiguity margins.

A clear incoming march can mark a tile unavailable. An ambiguous march is not treated as permission to interact.

The safe rule is:

unknown/ambiguous -> do not automatically interact.

This prevents a visual false positive from causing an unintended gather or hunt.

## 10. Monster model

Monster observations contain:
- monster name where OCR can identify it
- level
- K/X/Y
- screen position
- confidence
- march/occupancy evidence

The classifier now recognises the original requested monsters plus additional common monster names.

## 11. Monster lineup reference

The app contains a versioned local lineup database. V2.0.0 refreshes the six requested monsters with official Lords Mobile reference teams where available and community F2P/P2P alternatives where useful.

The database is explicitly labelled as reference information rather than an immutable game rule. Lineups can vary with monster level, hero ownership, gear, familiars and game-version changes.

The official IGG hunting guide is the primary reference for the official level-band recommendations.

## 12. Preference and ranking model

The user can select:
- resource classes
- resource levels
- monster levels
- overlay mode
- advanced automatic-action mode

Resource ranking combines:
- preference eligibility
- resource level
- available quantity when known
- distance from origin
- occupancy/incoming-march exclusion
- confidence

Discovery ranking is separate from action ranking. A generic resource can be displayed as discovered without pretending that its exact resource type has been proven.

## 13. Action safety architecture

Automatic interaction is not a direct consequence of detection.

The action path requires multiple independent checks:
- current-frame candidate
- target stability
- camera stability
- calibration validity
- march association
- popup state
- valid interaction point
- action-button detection
- candidate reconciliation
- scheduler safety gate
- pre-action revalidation
- durable execution journal
- Accessibility gesture dispatch
- post-action evidence/recovery

There are also durable recovery/quarantine mechanisms so a process restart cannot blindly replay an old action.

## 14. Gear requirement

The original product requirement specifies:
- gathering gear before gathering;
- hunting gear before hunting.

The architecture therefore treats gear preparation as a prerequisite workflow, not a cosmetic setting. Actual game-specific gear controls remain behind the same current-frame verification and accessibility safety gate as Gather/Hunt.

## 15. Why the current diagnostics are still visible

The large HUD used during development is intentionally an engineering console. It exposes:
- accepted/processed/dropped frames
- capture quality
- tile counts
- badge counts
- OpenCV status
- camera state
- validation stage
- action-button detection
- candidate counts
- top discovery

This makes real-device validation possible before the compact production presentation is trusted.

## 16. Evidence from real-device testing

The supplied sessions demonstrated:
- OpenCV becoming READY.
- Dozens of map tiles detected in a frame.
- More badge detections than tiles, which is expected because badges and objects are different intermediate signals.
- K/X/Y extraction.
- Processing around the low-hundreds-of-milliseconds on accepted frames.
- Healthy capture sessions.
- Zero action candidates while the safety conditions were not satisfied.

The earlier five-minute sessions also demonstrated stable capture lifecycle behaviour with no recorded stalls/restarts in the supplied diagnostic output.

## 17. Known limitations

1. Exact resource type is not guaranteed from a level badge alone. When map imagery is insufficient, the system must wait for OCR/detail evidence or a user-trained visual template.
2. Exact quantity is best read from the detailed resource popup.
3. Game UI changes can invalidate visual templates.
4. Accessibility gesture automation depends on the user enabling the service and Android's current accessibility behaviour.
5. Monster lineup data is reference data and should be refreshed after major game balance changes.
6. No implementation can guarantee correct game-state interpretation from screenshots alone. Uncertainty therefore blocks automated interaction.

## 18. Test strategy

Unit tests cover:
- coordinate calibration
- camera continuity
- target ranking
- target validation
- action scheduler safety
- action recovery
- temporal march signals
- OCR/popup parsing
- capture health/quality
- memory pressure
- real-device diagnostic summaries

Integration tests cover the path from live candidate to guarded dispatch and recovery.

## 19. Release procedure

1. Enable overlay permission.
2. Enable LM Companion AccessibilityService.
3. Start the scanner and approve a fresh screen-capture session.
4. Open Lords Mobile world map in landscape.
5. Verify K/X/Y is visible.
6. Run discovery-only testing first.
7. Confirm resources/monsters and coordinates.
8. Configure preferences.
9. Verify lineup reference.
10. Keep advanced automation OFF until target evidence is stable.
11. Only then enable advanced automation and validate one action at a time.

## 20. Engineering conclusion

The project has evolved from a simple overlay prototype into a layered, testable Android perception system.

The central design decision is that **vision is evidence, not authority**. Detection produces observations. Observations become targets only after fusion and preference filtering. Targets become actions only after current-frame validation and multiple safety gates.

That architecture is what makes it possible to keep developing resource gathering, monster hunting, gear preparation and hero selection without turning every OCR or CV error into a game interaction.

## 21. V2.0.0 release scope

V2.0.0 includes:
- production version bump
- discovery ranking independent of camera stability
- explicit distinction between discovered and action-ranked targets
- top-discovery HUD diagnostics
- expanded monster-name recognition
- named monster propagation into the world model
- refreshed official/community lineup reference data
- versioned lineup display with source and update date
- V1.0.x capture, OCR, OpenCV, calibration and guarded-action infrastructure

This release should be treated as the **integrated engineering baseline**. Further accuracy improvements should be driven by real-device evidence and labelled game screenshots rather than by weakening safety checks.
