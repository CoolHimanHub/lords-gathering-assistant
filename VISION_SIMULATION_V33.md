# V33 Coverage / Simulation Design

## Problem fixed
V32 could repeatedly inspect the same viewport. A candidate such as `1283,152` could therefore reappear on consecutive scans even though no new map area had been inspected.

## V33 validation model

1. Capture a screenshot.
2. Detect blue RSS level badges as candidates only.
3. OCR the game's map header and parse `X:<n> Y:<n>`.
4. Treat the first X/Y pair as the baseline viewport.
5. After a continuous scan, issue one bounded map swipe.
6. Do **not** assume the swipe succeeded because `dispatchGesture()` returned true.
7. Capture the next screenshot and compare X/Y with the previous viewport.
8. Only a changed X/Y viewport is accepted as a new inspection area.
9. If X/Y did not change once, issue one opposite-direction recovery swipe.
10. If viewport OCR is unavailable, coverage is paused rather than guessing.
11. Candidate keys combine viewport X/Y with quantised screen coordinates to suppress duplicate detections.
12. AUTO GATHER may probe only a fresh candidate from a newly validated viewport.
13. The opened tile panel remains authoritative; map artwork never authorises Gather.

## Sweep geometry

The 1536x707 supplied gameplay frames are used as the initial calibration:

- map X range: approximately 450..1280
- map Y range: approximately 125..545
- horizontal drag: 500 px
- vertical drag: 260 px
- gesture duration: 650 ms

These values are deliberately conservative. They can be calibrated on-device if a particular game UI scale differs.

## Expected overlay output

```text
Scan #N  X:227 Y:375
NEW VIEWPORT
Found 1 badge candidates:
1. RSS? @ 1283,152 (87%)
```

followed by:

```text
Coverage sweep
RIGHT
Waiting for X/Y to change...
```

If the next screenshot still says `X:227 Y:375`, that area is not accepted as new and the recovery direction is attempted once.

## Safety gates

- No Gather action is sent merely because a badge was detected.
- Repeated viewport = no automatic candidate action.
- Missing viewport OCR = no automatic coverage movement.
- Panel verification must pass before the Gather control is tapped.
- STOP cancels the scan and sweep runnables.
