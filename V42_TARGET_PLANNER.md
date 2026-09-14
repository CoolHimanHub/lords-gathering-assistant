# V4.2 — Target Planner

Added `ResourceTargetPlanner.kt` to the existing `v54-compact-overlay` Android project.

## Purpose

Use the existing `ScreenAnalyzer.RssDetection` output without duplicating OCR or badge detection.

The planner:

1. rejects occupied detections;
2. rejects low-confidence detections;
3. ranks candidates by confidence, detected level, and modest screen distance;
4. returns a deterministic target key for de-duplication;
5. leaves the final Gather decision to the existing tile-panel verifier.

## Important current limitation

`ScreenAnalyzer` currently emits `type="RSS?"` for visual badge candidates. Therefore V4.2 does **not** guess resource type or quantity. Those fields should come from the authoritative selected-tile panel OCR already present in the project.

Next integration: connect the planner to `GatheringAccessibilityService` immediately after `analyzeScreenshot()`, then probe the top candidate and require `TilePanelVerifier` confirmation before Gather.
