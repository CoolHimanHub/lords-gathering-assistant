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


### V0.4.50 — Fail-closed recovery epoch overflow

- Recovery epoch advancement now detects Long.MAX_VALUE instead of wrapping the provenance boundary back into a previously usable identity.
- Restart quarantine and deliberate UNKNOWN recovery fail closed when no fresh recovery epoch can be allocated.
- Recovery epoch exhaustion is represented explicitly in the lifecycle and is not eligible for automatic retry.
- The capture service keeps quarantine/journal state intact when a recovery boundary cannot be established.
- Added regression coverage for reset and restart-recovery overflow at the exact Long.MAX_VALUE boundary.
- Automatic Gather/Hunt actions remain disabled by default.

### V0.4.49 — Fail-closed action identity overflow

- Durable action-attempt allocation now detects Long.MAX_VALUE instead of allowing numeric overflow to wrap the identity into a negative value.
- Overflow is treated as an allocation failure, so the orchestrator creates no action session and cannot reach guarded dispatch.
- Added regression coverage for normal advancement, the exact maximum boundary, and the no-wrap invariant.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.48 — Durable provenance consistency diagnostics

- Live safety diagnostics now expose the durable in-flight journal attempt ID and recovery epoch alongside the current action provenance.
- Diagnostics explicitly identify legacy journal entries whose recovery epoch was written before epoch provenance existed.
- Startup reconciliation is now operator-visible through the reconciled startup epoch and restart-quarantine state.
- When restart quarantine is active, diagnostics show that deliberate recovery is required instead of implying that automatic retry is available.
- Added regression coverage for the journal/current-attempt distinction, durable epoch provenance, startup reconciliation, and active quarantine messaging.
- Automatic Gather/Hunt actions remain disabled by default.


### V0.4.47 — Restart provenance reconciliation

- Restart recovery now reconciles the persisted recovery epoch with the epoch stored in any in-flight journal before constructing the new action orchestrator.
- The recovered orchestrator therefore starts from the highest known durable epoch and advances it again when entering UNKNOWN quarantine.
- Legacy journals without an epoch remain safely quarantined while the persisted recovery epoch remains authoritative.
- This prevents stale journal metadata from ever causing a lower recovery epoch to be reused after restart.
- Automatic Gather/Hunt actions remain disabled by default.

### V0.4.46 — Durable action provenance boundary

- Added an explicit immutable pre-dispatch provenance record binding the action attempt ID, recovery epoch, and dispatch timestamp.
- The durable in-flight journal now persists the recovery epoch alongside the attempt ID before any guarded Accessibility gesture.
- Live dispatch requires the provenance record to still match the active action session before the journal barrier can open the gesture path.
- A stale attempt or recovery epoch therefore cannot cross a manual recovery/restart boundary into a new dispatch.
- Legacy journal entries remain restart-quarantined and cannot satisfy the current-session provenance match.
- Added regression coverage for attempt mismatch, recovery-epoch mismatch, and missing session provenance.
- Automatic Gather/Hunt actions remain disabled by default.

### V0.4.44 — Durable action attempt identity

- Action attempt IDs are now persisted independently of the in-flight action journal.
- Attempt allocation is committed before guarded dispatch, preventing clean process/service restarts from silently reusing a prior attempt ID.
- Recovered legacy/in-flight attempt IDs advance the durable allocator before a fresh attempt is created.
- Added regression coverage for monotonic durable attempt allocation.
- Automatic Gather/Hunt actions remain disabled by default.

### V0.5.0 — Action safety simulation boundary

- Added an executable safety-simulation matrix covering valid dispatch, target change before dispatch, popup disappearance without proof of outcome, unrelated march evidence, camera instability, restart quarantine, durable attempt-ID failure, and recovery-epoch exhaustion.
- The simulations run entirely against the pure action core; they do not require Android Accessibility, a live screen capture session, or a real game connection.
- Added a second recovery-policy gate directly inside `ActionOrchestrator.request()`. The service-level policy remains in place, but the core now independently refuses new automatic attempts while an action is `UNKNOWN`, `REQUESTED`, `REVALIDATED`, or `WAITING_FOR_RESULT`.
- The V0.5 invariant is explicit: **uncertain or stale evidence must never create a new automatic gesture.**
- Automatic Gather/Hunt actions remain disabled by default.

### V0.5.1 — Multi-target action scheduler

- Added a pure `ActionScheduler` for multiple detected targets.
- Candidates are accepted only when they meet the minimum stability requirement and are already marked validation-safe.
- Selection order is deterministic: priority, then stability, then queue age.
- An in-flight action blocks selection of another candidate.
- A 1.5-second dispatch cooldown prevents immediate consecutive actions.
- Claiming a candidate only removes it from the queue; it does **not** dispatch a gesture.
- The selected candidate must still pass the existing pre-action revalidation, durable provenance, journal, interaction gate, and post-action verification pipeline.
- Added regression coverage for priority, stability, safety, cooldown, in-flight blocking, duplicate targets, and candidate claiming.
- Automatic Gather/Hunt remains disabled by default.

### V0.5.1 follow-up — Live-scan queue reconciliation

- The multi-target scheduler now reconciles its queue against the latest scan.
- Targets that disappear from the current safe candidate set are removed.
- A target that becomes validation-unsafe or falls below the stability threshold is removed before selection.
- This prevents stale queued targets from surviving across changing frames.
- Scheduler selection remains subordinate to the existing recovery, revalidation, provenance, journal, interaction, and post-action verification boundaries.

### V0.5.1 follow-up — Live capture integration boundary

- The live capture service now feeds the scheduler from the existing current-frame validated action target.
- Scheduler selection is additionally checked against the existing lifecycle, automatic-action preference, restart quarantine, and recovery-epoch persistence state.
- The scheduler cannot manufacture actionable coordinates for merely ranked map targets; an interaction point must already come from the existing scanner/action-button validation path.
- A scheduled target must still match the previous scan before entering the existing pre-action revalidation and guarded dispatch pipeline.
- Queue reconciliation remains frame-driven, so a target change causes the prior candidate to stop being actionable.

### V0.6.0 — Persistent action audit/event log

- Added a bounded persistent action audit log retaining the latest 500 action-provenance events.
- Audit records can capture timestamp, event type, attempt ID, recovery epoch, target identity, and diagnostic detail.
- Live capture records scheduler selection, action request, dispatch barrier, dispatch result, verification result, deliberate recovery, and restart quarantine events.
- The audit log is diagnostic only: logging never authorizes dispatch and logging failures do not open the gesture path.
- Target provenance is serialized and restored so audit history remains useful across service/process restarts.
- Added instrumentation tests for provenance round-trip and bounded retention.
- Automatic Gather/Hunt actions remain disabled by default.

### V0.6.0 follow-up — Read-only audit diagnostics

- Evidence & Safety Diagnostics now shows the latest persistent action-audit events.
- The timeline is read-only and has no control over scheduling, recovery, validation, or gesture dispatch.
- Operators can correlate attempt ID, recovery epoch, target identity, dispatch, verification, and quarantine events from the same diagnostics screen.

### V0.6.1 — Complete scheduler audit coverage

- Scheduler admission is now persistently audited for safe current-frame candidates.
- Scheduler safety blocks are recorded with their concrete block reason.
- Identical repeated scheduler-block events are deduplicated to prevent frame-rate log flooding.
- Added instrumentation coverage for audit-event deduplication.
- The audit trail remains diagnostic-only and cannot authorize or bypass an action.

### V0.6.4 — Independently validated live action candidates
- LiveMapScanner now exposes current-frame action candidates with observation, real detected action-button point, validation result, and per-target stability evidence
- action-button association is fail-closed and requires a unique current-frame target within a bounded screen distance
- live scheduler now consumes all independently validated candidates instead of manufacturing candidates from the single planner winner
- queued targets are still reconciled against the latest safe frame and dropped targets remain auditable
- automatic actions remain disabled by default and every candidate still passes the existing revalidation/provenance/dispatch safety chain

### V0.6.3 — Audit completeness and reconciliation diagnostics
- scheduler reconciliation now returns targets dropped from the durable candidate queue
- live capture records CANDIDATE_DROPPED when a target disappears from the latest safe candidate set
- explicit DISPATCH_BARRIER_FAILED audit event records a failed durable in-flight barrier before recovery
- explicit RECOVERY_EPOCH_PERSISTENCE_FAILED audit event records a failed recovery-boundary persistence attempt
- audit persistence tests cover the new event taxonomy
- audit remains diagnostic only and never authorizes gesture execution

### V0.6.2 — Complete lifecycle audit coverage

- Added persistent audit events for revalidation success/failure, attempt-ID persistence failure, UNKNOWN entry, and verification timeout.
- Live capture now records the actual request outcome instead of assuming every scheduler selection became a valid action request.
- Revalidation failures are explicitly recorded before the action can reach dispatch.
- UNKNOWN and verification-timeout outcomes are recorded without changing the existing non-retryable recovery policy.
- Added persistence coverage for the new lifecycle failure event types.
- Automatic actions remain governed by the existing fail-closed safety chain.

### V0.6.5 — Preserve planner ranking through live scheduling

- Live action candidates now retain the existing TargetPlanner ranked position and native score.
- The live scheduler no longer uses a temporary monster-over-resource priority as its primary ordering rule.
- Scheduler ordering preserves planner rank first, then planner score, stability, and queue age.
- Candidates that are independently validated but absent from the planner remain fail-closed and are ordered after planner-ranked candidates.
- Regression tests cover planner-rank ordering and score tie-breaking.
- Automatic actions remain disabled by default; planner ranking does not bypass current-frame validation, revalidation, provenance, journaling, or guarded dispatch.

### V0.6.5 follow-up — Completed-target requeue suppression

- Verified successful targets are suppressed from immediate scheduler re-entry for a bounded 5-second window.
- Suppression is scheduler-local and expires automatically, allowing a genuinely fresh later observation to become eligible again.
- Regression tests cover immediate suppression and re-entry after the window.

### V0.6.5 follow-up — Latest-frame queue authority

- Scheduler reconciliation now rebuilds its queued candidate set from the latest safe scan.
- A target retaining the same identity cannot keep stale planner rank, score, stability, or queue timestamp from an earlier frame.
- A changed target identity is explicitly dropped and replaced by the latest candidate.
- Regression tests cover same-identity metadata replacement and target-identity change.

### V0.6.5 safety correction — Failed verification is retry-eligible

- Scheduler completed-target suppression is now applied only after `SUCCEEDED` post-action verification.
- A `FAILED` verification no longer marks the target as completed, preventing a failed action from being treated as successful completion.
- `UNKNOWN` remains non-retryable automatically and is handled by the existing recovery boundary.

### V0.6.5 follow-up — Multi-target failure isolation

- Claiming one candidate removes only that candidate from the scheduler.
- Revalidation or dispatch failure for the claimed target does not discard independently validated queued targets.
- Regression coverage verifies the remaining target can still be selected after the first target fails.

### V0.6.5 follow-up — Stable target identity

- Scheduler identity is now based on world coordinate, target kind, level, and action kind.
- The transient screen interaction point is no longer part of scheduler identity, so camera/UI movement does not create a duplicate logical target.
- The latest action point remains authoritative for dispatch and is still checked by the fail-closed pre-action revalidator.
- Completed-target suppression follows the same stable identity across interaction-point movement.

- The core ActionOrchestrator completed-target guard now uses the same stable identity, so a moved interaction point cannot bypass successful-completion protection.


### V0.6.5 follow-up — Stable identity across live-frame point movement

- Live scheduler reconciliation now matches the previous and current action candidates by stable target identity rather than full screen-point equality.
- A moving interaction point therefore does not discard an otherwise continuous logical target between frames.
- The latest frame remains authoritative for the actual action point, and the existing pre-action revalidator still enforces the 45 px interaction-point drift limit before dispatch.
- This separates logical target continuity from transient screen geometry without weakening the final fail-closed interaction gate.


### V0.6.5 follow-up — Complete stable-identity lifecycle handoff

- ActionOrchestrator session/revalidation/dispatch/verification continuity now compares logical target identity separately from transient screen point.
- A moved interaction point can remain the same logical action session; the latest point is still accepted only through PreActionRevalidator's explicit drift and action checks.
- Cleared a stale completed-target reset reference left from the identity migration.
- Added regression coverage proving point movement within the permitted drift remains the same logical session.


### V0.6.5 safety correction — Stable identity through post-action verification

- Post-dispatch lifecycle continuity now also uses stable target identity when associating the active session with later observations.
- Screen-point movement therefore cannot accidentally terminate an otherwise valid logical post-action session.
- This does not relax evidence requirements: target-state evidence, camera stability, march association, and the existing verification rules remain unchanged.


## V0.7.6 — Fail-closed camera continuity gate

- Camera state now becomes `UNSTABLE` when an established frame loses the minimum shared-target continuity needed to prove stability.
- The first frame remains neutral, but subsequent sparse continuity cannot silently reopen the action path.
- Added regression coverage for insufficient shared-target continuity.
- App version is now 0.7.6.

## V0.7.5 — Viewport discontinuity quarantine

- Invalid viewport frames now reset camera-anchor, camera-state, temporal-observation, and target-stability continuity.
- Prevents stale screen-space state from being associated with a later frame after resolution/orientation or capture discontinuity.
- Added camera reset regression coverage.
- App version is now 0.7.5.

## V0.7.4 — Ambiguous camera-anchor fail-closed hardening

- Repeated semantic anchors now reject near-tied nearest-neighbor matches instead of guessing an identity.
- Added regression coverage for ambiguous repeated-target movement.
- This protects the camera model from being opened by a plausible but incorrect anchor association.
- App version is now 0.7.4.

## V0.7.3 — End-to-end camera movement regression

- Added multi-frame camera simulation coverage proving that pan + zoom preserve the same world identity after camera correction.
- Added rotation regression coverage proving rotation is not silently accepted as ordinary pan/zoom.
- Existing CameraStateTracker behavior remains the final stability gate: panning/zooming stays outside automatic action eligibility.
- App version is now 0.7.3.

## V0.7.2 — Camera anchor geometry hardening

- Camera fitting now requires at least three non-collinear world anchors, preventing a two-point fit from masking rotation or other 2D geometry changes.
- Repeated semantic labels are matched one-to-one across adjacent frames by bounded nearest-neighbor screen distance instead of being discarded wholesale.
- Anchor association is based on the previous frame's world coordinate and the current frame's screen position, avoiding self-referential current-frame camera fitting.
- Large semantic movement beyond the association threshold is rejected rather than converted into camera motion.
- Added regression coverage for repeated-label matching, far-movement rejection, two-anchor rejection, and collinear-anchor rejection.
- App version is now 0.7.2.

## V0.7.1 — Live camera-invariant coordinate integration

- LiveMapScanner now performs a provisional affine vision pass, extracts only unique semantic cross-frame anchors, and estimates camera scale/translation from those anchors.
- When the camera model passes its residual/geometry gate, the scanner performs a second coordinate-resolution pass using the camera-aware world model.
- Temporal target stabilization and camera-state assessment run exactly once on the final coordinate pass; a single frame cannot artificially advance stability.
- Ambiguous duplicate semantic observations are excluded from camera anchors.
- Existing CameraState.STABLE planning and action-validation gates remain unchanged, so camera correction does not authorize actions by itself.
- Added regression coverage for unique anchor association, duplicate-anchor rejection, and reset behavior.
- App version is now 0.7.1.

## V0.7.0 — Camera-invariant world-coordinate foundation

- Added a camera-aware world model that keeps the learned affine world geometry separate from transient camera state.
- Live world/screen anchors estimate only uniform camera scale (zoom) and screen translation (pan); the base world mapping is not refit on every camera movement.
- Screen-to-world resolution normalizes the current camera state before using the established affine inverse.
- The model fails closed when there are too few anchors, non-finite/invalid scale, or excessive anchor residual.
- Rotation/skew changes are intentionally not absorbed as if they were ordinary pan/zoom; those conditions remain invalid until the camera model is recalibrated.
- Added JVM regression coverage for exact pan+zoom recovery, inconsistent-anchor rejection, and insufficient-anchor rejection.
- This is the V0.7 coordinate-model foundation; live scanner integration remains gated on camera-anchor extraction and validation.

### V0.6.8 — Restart and process-failure safety verification

V0.6.8 extends lifecycle regression coverage around restart recovery, UNKNOWN outcomes, and recovery-epoch exhaustion. An unresolved or quarantined action remains ineligible for automatic retry; only the established safe terminal lifecycle states may cross the automatic recovery boundary.

### V0.6.8 follow-up — Cross-component restart safety simulation

- Added an executable JVM regression that follows a validated scheduler candidate through REQUESTED → REVALIDATED → WAITING_FOR_RESULT.
- The test then simulates process/service death by constructing a fresh orchestrator from the last durable recovery epoch and restoring the in-flight attempt as UNKNOWN.
- Restart quarantine is verified at both the lifecycle recovery-policy boundary and the scheduler safety gate.
- Deliberate recovery is then required to establish a new recovery epoch before the automatic safety gate can reopen.
- This verifies the critical invariant across components: **a dispatch that may have reached the game cannot become an automatic retry merely because the process restarted.**

## V0.6.7 — Live action eligibility boundary

V0.6.7 hardens the scanner-to-scheduler boundary. A live candidate is eligible for automatic scheduling only when the current-frame validation stage is `SAFE_TO_INTERACT`, the planner rank is a valid non-negative value, and the planner score is finite. Visually detected or otherwise independently validated candidates that are not represented by the current planner remain diagnostic-only and cannot cross into automatic scheduling.

Regression coverage verifies ranked candidates, unranked candidates, non-finite scores, unsafe validation, and invalid negative planner ranks. CI status must be checked on the actual GitHub Actions run before this milestone is considered verified.

## V0.6.6 — CI unit-test verification boundary

- GitHub Actions now runs the Android JVM unit-test suite before assembling the debug APK.
- The action-safety regression suite therefore becomes a required build-stage verification step rather than relying only on source-level review.
- APK assembly remains a separate step after tests complete successfully.
- Latest commits still require an actual GitHub Actions run before CI success can be claimed.


## V0.7.7 — Action-candidate camera continuity gate

- Camera assessments now expose an explicit `continuityForActions` proof bit that is false on the first frame and whenever the minimum shared-target continuity is lost.
- Live action candidates carry that continuity proof into the scheduler boundary.
- Scheduler eligibility now requires established camera continuity in addition to `SAFE_TO_INTERACT`, planner rank/score validity, and the existing validation chain.
- This prevents a candidate from crossing the automatic-action boundary merely because a single frame is classified as stable; camera continuity must first be established across frames.
- Added regression coverage for continuity-present and continuity-missing candidates.
- Automatic Gather/Hunt actions remain disabled by default.

## V0.8.1 — Live capture runtime hardening

- Captured Image resources are now closed exactly once even when bitmap conversion fails, and conversion failures release the busy gate instead of wedging the capture loop.
- Image acquisition failures are handled fail-closed and recorded as dropped frames.
- Frames older than the runtime capture-age threshold are rejected before CV/OCR processing so stale screen content cannot enter the live planner.
- Capture telemetry now separates stale-frame drops from ordinary drops.
- Virtual-display creation failures tear down capture resources and stop the service instead of leaving a partially initialized session alive.
- Added regression coverage for stale-frame accounting.
- Automatic Gather/Hunt actions remain disabled by default; these runtime hardening changes do not relax any action-safety gate.
- The next milestone is sustained real-device/game validation: restart recovery, rotation/display changes, memory pressure, long-running capture, and detector accuracy.
## V0.8.0 — Live device readiness foundation

- Added deterministic capture-session telemetry for frame arrival, accepted/dropped frames, viewport resets, processing latency, maximum latency, and average latency.
- Capture lifecycle now tears down any previous reader/projection before starting a new capture request, preventing duplicate capture resources after service restarts or repeated permission flows.
- MediaProjection stop is treated as a capture-session termination event and stops the service rather than leaving a stale scanner session alive.
- Capture-resolution changes are handled fail-closed: the mismatched frame is dropped, the viewport guard is reset, and the following frame can establish a fresh screen-space baseline.
- The live overlay now reports basic capture health (average processing time and drop rate) alongside the existing camera/validation/action lifecycle diagnostics.
- Added unit coverage for capture telemetry accounting and reset behavior.
- Automatic Gather/Hunt actions remain disabled by default; telemetry is diagnostic and does not relax any action-safety gate.
- App version is now 0.8.0.

## V0.8.2 — Capture stall watchdog and fail-closed runtime recovery

- Added a deterministic capture watchdog with a 3-second no-frame threshold after capture-session start.
- Each arriving capture frame refreshes the watchdog; a genuine stall produces one edge-triggered failure signal rather than repeated resets.
- A stalled capture session is stopped fail-closed, with no relaxation of camera continuity, validation, scheduler, or action-execution gates.
- A capture viewport/dimension change is now treated as a display-geometry transition; the active reader is stopped instead of re-baselining onto potentially stale screen geometry, and the user can restart scanning cleanly.
- Watchdog state is cleaned up on normal capture teardown and MediaProjection termination.
- Added JVM regression tests for the exact timeout boundary, one-shot stall behavior, frame recovery, and stopped-session behavior.
- App version is now 0.8.2.
