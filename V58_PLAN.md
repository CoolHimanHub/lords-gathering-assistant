# V58 implementation plan

## Runtime diagnostics
- Keep diagnostics opt-in.
- Record scan counters and tile decisions as structured JSONL.
- Record representative screenshots only when diagnostics is enabled.
- Never upload data automatically.

## Decision pipeline
1. Reconstruct visible isometric grid.
2. Classify object and RSS type.
3. Estimate RSS level 1-5.
4. Track occupancy and approaching/marching players across frames.
5. Reject occupied, approaching, uncertain, or non-RSS tiles.
6. Rank remaining candidates.
7. Verify tile panel before Gather.
8. Log action and result.

## UX
- Increase overlay touch targets and status readability.
- Keep normal scan path lightweight.

## Validation
- Build debug APK.
- Run existing unit/simulation tests.
- Do not claim device runtime validation without device telemetry.
