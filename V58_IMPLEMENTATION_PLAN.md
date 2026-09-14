# V58 implementation plan

## Goal
Turn the existing V57.4 Android app into a self-diagnosing, tile-aware gather assistant while preserving the existing AccessibilityService and verified gather flow.

## Acceptance criteria
- Larger overlay touch targets and readable status.
- Diagnostics session model with scan counters and per-tile decisions.
- Representative screenshot capture during diagnostics, bounded/rate-limited.
- Persistent diagnostic logs: tile position, resource type, level, confidence, occupancy, movement/approach state, accept/reject reason.
- RSS candidate ranking only from unoccupied, non-approaching, high-confidence tiles.
- Tile-panel verification and gather result recorded.
- Export Diagnostics ZIP.
- Simulation/test mode that never dispatches gather gestures.
- Normal mode remains lightweight; diagnostics are opt-in.
- Build succeeds and APK artifact is produced by GitHub Actions.

## Current technical constraint
ScreenAnalyzer currently detects blue RSS-like badges and classifies nearby pixels, but its RssDetection.level remains 0 and occupied remains false. V58 must not treat those fields as authoritative. Add a semantic tile layer that derives/validates level and occupancy before a candidate can enter the gather queue.

## Safety rule
Unknown or ambiguous tiles are rejected. AUTO GATHER must remain blocked unless grid state is locked and tile-panel verification confirms Gather availability.
