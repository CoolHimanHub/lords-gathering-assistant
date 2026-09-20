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


### V0.4.11 — temporal march and viewport safeguards

- Temporal march association: blue/cyan and orange march contours now require repeated nearby evidence before becoming stable march signals; isolated one-frame contours are ignored by the occupancy fusion layer.
- Viewport consistency guard: capture dimensions are locked for the current scanner session. A dimension change stops coordinate-driven scanning rather than silently applying calibration from a different screen geometry.
- Added regression tests for single-frame march rejection, repeated march confirmation, stale march expiry, stable viewport dimensions, dimension changes, and invalid dimensions.
- Added manual GitHub Actions dispatch support so the Android build can be explicitly rerun when needed.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.12 — explicit target-state safety

- Target planning now requires explicit `occupied=false` and `incomingTroops=false`; unknown state is no longer treated as free.
- Added regression coverage for unknown versus explicitly-free resource observations.
- This keeps target ranking conservative while camera/world-state continuity work proceeds.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.13 — unknown-state integrity

- Fixed a state-safety gap where OCR/fusion could represent missing incoming-troop evidence as `false`.
- Occupancy and incoming-troop fields now remain explicitly unknown (`null`) when the frame has no evidence either way.
- A nearby march signal can explicitly promote a node to occupied/incoming, while an authoritative selected-node popup can explicitly clear that state.
- Temporal tracking no longer promotes high confidence by itself into occupied state; occupancy requires explicit evidence.
- Added regression coverage so high-confidence unknown observations remain unknown and distant march signals do not create false free/occupied state.
- Target planning continues to require explicit `occupied=false` and `incomingTroops=false`.
- Automatic Gather/Hunt actions remain disabled by default.


## V0.4.14 — World Identity & Camera-State Safety

- Added stable observation identity from kingdom/X/Y plus target kind and level.
- Added explicit observation evidence reasons for OCR, march, temporal confirmation, and unknown state.
- Added camera-state tracking using repeated world identities and screen displacement.
- Camera states are **STABLE**, **PANNING**, or **UNSTABLE**.
- Target planning is paused while the camera is panning or unstable, preventing coordinate-driven selection during viewport transitions.
- Added regression tests for consistent camera movement and contradictory screen shifts.
- Automatic in-game actions remain disabled.


### V0.4.18 — Pre-action target revalidation

- Added an explicit **pre-action revalidation** layer between target selection and any future Accessibility gesture.
- The selected target snapshot now carries world coordinate, target kind, level, intended action, and interaction point.
- The latest scan must still match the same coordinate/kind/level and action before a gesture can proceed.
- Interaction-point drift beyond 45 px is rejected as stale/invalid.
- Any latest validation failure is propagated and blocks the gesture.
- Added regression tests for unchanged targets, target changes, action changes, point drift, and newly occupied targets.
- LmAccessibilityService.tapRevalidated() now routes future gestures through this final revalidation step.
- This does not enable automatic gameplay actions; the existing opt-in/disabled-by-default behavior remains unchanged.


### V0.4.19 — Guarded action-execution policy

- Added an explicit action-execution policy layer after target revalidation.
- Automation must be explicitly enabled before an action request can be accepted.
- Invalid or non-safe validation results are rejected.
- Added a 1.5-second global dispatch cooldown to prevent rapid repeated gestures.
- Added a 3-second same-target duplicate window so consecutive scans cannot repeatedly trigger the same target.
- Dispatch history can be reset when the game state changes or a new execution session starts.
- The controller only makes an execution decision; it does not itself dispatch a gesture.
- Accessibility gestures therefore remain behind the existing validation and interaction gates.


### V0.4.20 — Action lifecycle + post-action verification

- Added an explicit lifecycle: REQUESTED → REVALIDATED → WAITING_FOR_RESULT → SUCCEEDED / FAILED / UNKNOWN.
- Dispatch is accepted only after the existing execution-policy gate and pre-action revalidation.
- Post-action verification is deliberately conservative: an explicitly associated own march is positive success evidence.
- Popup disappearance alone is not treated as success.
- Popup disappearance combined with target removal or an explicit occupied state is treated as success.
- Explicit action rejection becomes FAILED.
- Verification timeout becomes UNKNOWN rather than assuming success.
- The lifecycle controller remains UI/Accessibility agnostic; it does not itself send gestures.
- Automatic gameplay remains opt-in and disabled by default.


### V0.4.21 — Post-action state evidence

- Added a post-action state verifier that compares the selected world target with the next observed state.
- Detects popup disappearance, target removal, and explicit occupied/incoming state at the same target.
- Popup disappearance alone remains insufficient to claim success.
- The verifier deliberately does **not** infer that a detected march belongs to our action; march ownership/association requires stronger evidence.
- Added regression tests for target association, removal, occupied state, unrelated observations, and popup-only disappearance.
- Automatic gameplay remains opt-in and disabled by default.


### V0.4.22 — Action-to-march association

- Added a pure action-to-march association tracker for post-dispatch evidence.
- The tracker snapshots march signals before an action and rejects signals that were already present near the action point.
- A post-action candidate must appear near the selected interaction point, persist across frames, and show consistent motion before producing `OWN_MARCH_CONFIRMED`-compatible evidence.
- Stationary, pre-existing, stale, or inconsistent-trajectory signals do not confirm ownership.
- The component is UI/Accessibility agnostic and does not dispatch gestures or enable automation.
- The next integration step is to feed this evidence into the existing action lifecycle after a real dispatch, while keeping automatic gameplay disabled by default.


### V0.4.23 — March departure-direction hardening

- Strengthened action-to-march association by checking the first detected movement against the selected interaction point.
- A candidate that moves toward the action point is not accepted as own-march evidence.
- Added regression coverage for an opposite-direction trajectory.
- This remains evidence-only; no automatic gesture dispatch is enabled.

### V0.4.24–V0.4.33 — Guarded action execution, evidence provenance & safety hardening

The action layer was progressively integrated without making gameplay automation the default:

- **V0.4.24:** added the integrated action orchestrator connecting selection, lifecycle, pre-action revalidation, dispatch decision, and post-action observation.
- **V0.4.25:** wired guarded action orchestration into the live scanner while keeping the Accessibility gesture path explicitly opt-in.
- **V0.4.26:** bounded post-action observation and prevented immediate retry loops after incomplete verification.
- **V0.4.27/V0.4.28:** hardened completed-target handling, camera-stable verification, target identity checks, and multi-frame post-action confirmation.
- **V0.4.29/V0.4.30:** added structured post-action evidence provenance and session-bound march trajectory history so diagnostics can show why an action was considered successful or inconclusive.
- **V0.4.31:** added a live evidence/safety diagnostics screen and scanner-to-diagnostics state bridge, including camera scale, target stability, validation reasons, lifecycle state, and post-action evidence.
- **V0.4.32:** hardened march-to-tile association. Multiple nearby marches can now be reported as ambiguous instead of being arbitrarily assigned to a target; ambiguous association blocks interaction validation.
- **V0.4.33:** integrated temporal target stability into the final validation gate and requires the current-frame target, stable camera state, valid calibration, explicit target state, popup identity, target-specific action, and non-ambiguous march association before an interaction can be considered safe.

Current safety pipeline:

capture → detect → fuse → temporal state → camera state → target stability → target selection → popup/action validation → march-association validation → pre-action revalidation → guarded dispatch → multi-frame post-action verification → evidence provenance

### V0.4.34 — Guarded recovery policy

- Centralized automatic action recovery policy in the lifecycle layer.
- `IDLE`, `SUCCEEDED`, and `FAILED` may begin a fresh automatic attempt after the normal validation chain.
- `REQUESTED`, `REVALIDATED`, and `WAITING_FOR_RESULT` remain in-flight and cannot start another attempt.
- `UNKNOWN` is explicitly non-retryable because the previous gesture outcome is uncertain; automatic retry could duplicate a successful action.
- Live diagnostics now expose whether automatic recovery is currently allowed or blocked.
- Added regression coverage for all lifecycle recovery states.
- Automatic Gather/Hunt actions remain disabled by default and all gesture dispatch remains behind explicit user opt-in plus the complete safety chain.

### V0.4.35 — Deliberate UNKNOWN recovery

- Added a diagnostics-screen recovery control for an action lifecycle stuck in `UNKNOWN`.
- Recovery is accepted only when the active lifecycle is `UNKNOWN` and automatic actions are disabled.
- The diagnostics UI only raises a process-local request; the capture service performs the guarded reset.
- The request is one-shot and does not bypass target validation, Accessibility gating, or automatic-action policy.
- This prevents an uncertain action from being automatically retried while providing a deliberate operator recovery path.
- Added regression coverage for the one-shot recovery request bridge.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.36 — Action attempt identity

- Added a monotonically increasing action `attemptId` to each orchestrator session.
- Post-action evidence records now carry the exact attempt identity that produced the evidence.
- Manual UNKNOWN recovery does not reset the attempt counter, preserving provenance across recovery boundaries.
- Added regression coverage proving the successful post-action evidence record is bound to attempt 1.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.37 — Cross-attempt evidence isolation

- Added regression coverage for a completed action followed by a fresh action attempt on the same target.
- The second attempt starts with a new `attemptId`, clears prior evidence, and treats the previous march signal as baseline/stale evidence.
- Only newly observed trajectory evidence can contribute to the second attempt's post-action confirmation.
- This protects against stale march evidence leaking across retries or deliberate recovery boundaries.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.38 — Operator-visible action provenance

- Live safety diagnostics now expose the current action attempt ID.
- Post-action evidence displays its originating attempt ID and whether it matches the current action attempt.
- This makes cross-attempt evidence isolation observable during manual diagnostics instead of relying only on internal guards.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.39 — Restart-safe action quarantine

- Added a durable in-flight action journal written before Accessibility dispatch.
- If the capture service/process restarts while an action may already have reached the game, the new service restores a non-retryable UNKNOWN quarantine instead of starting a fresh automatic attempt.
- The quarantine remains active even with Automatic actions disabled until the deliberate diagnostics recovery control clears it.
- Definitive dispatch failure and verified success/failure clear the journal; UNKNOWN does not.
- Added lifecycle regression coverage for restart recovery state.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.40 — Fresh recovery boundary & durable dispatch barrier

- Manual UNKNOWN recovery now clears the previous scan baseline, so a recovered action cannot immediately reuse pre-recovery target/march evidence.
- The in-flight action journal now uses synchronous SharedPreferences commit before Accessibility dispatch; if the durable barrier cannot be persisted, the gesture is not dispatched.
- Definitive dispatch failure and verified outcomes continue to clear the journal, while unresolved outcomes remain quarantined.
- Added this boundary as the next safety checkpoint before further automation features.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.41 — Recovery epoch provenance

- Added a monotonic recovery epoch to action sessions so each deliberate recovery/restart boundary is distinguishable from the previous execution context.
- Post-action evidence now records the recovery epoch as well as the action attempt ID.
- Live diagnostics expose the current recovery epoch and evidence epoch for operator verification.
- Restart quarantine creates a new epoch; deliberate recovery/reset creates the next fresh epoch before a new action attempt.
- Added regression coverage proving a fresh post-recovery attempt cannot share the prior recovery epoch.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.42 — Persistent recovery epoch

- Recovery epoch is now persisted independently of the in-flight action journal.
- A clean process/service restart retains the last recovery epoch instead of silently returning to epoch 0.
- Restart quarantine and deliberate recovery continue to advance the epoch before a new execution context.
- Added regression coverage for seeding a new orchestrator from persisted epoch state.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.43 — Fail-closed recovery epoch persistence

- Recovery epoch persistence failures are now explicit in the live safety diagnostics.
- Automatic execution is blocked whenever the current recovery epoch is not durably persisted.
- Deliberate UNKNOWN recovery does not clear the in-flight journal or restart quarantine until the new epoch is successfully persisted.
- Persistence is retried safely before leaving quarantine; a transient storage failure therefore cannot silently reopen automatic execution.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.45 — Fail-closed attempt identity persistence

- Action-attempt ID persistence failures are now represented as an explicit lifecycle failure instead of escaping as an exception.
- A failed durable allocation creates no action session and cannot reach pre-action revalidation or Accessibility dispatch.
- Automatic execution may safely retry on a later scan because no gesture was dispatched and no ambiguous in-flight state was created.
- Added regression coverage proving allocator failure stops before session creation and dispatch.
- Automatic Gather/Hunt actions remain disabled by default.

### V0.4.44 — Durable action attempt identity

- Action attempt IDs are now persisted independently of the in-flight action journal.
- Attempt allocation is committed before guarded dispatch, preventing clean process/service restarts from silently reusing a prior attempt ID.
- Recovered legacy/in-flight attempt IDs advance the durable allocator before a fresh attempt is created.
- Added regression coverage for monotonic durable attempt allocation.
- Automatic Gather/Hunt actions remain disabled by default.
