# V35 Coordinate OCR Validation

## Problem reproduced from the supplied recording

The V34 build occasionally displayed `Viewport X/Y not readable` even when the game HUD visibly showed coordinates. This stopped the coverage sweep unnecessarily.

## V35 change

`ViewportOcrReader` now:

1. Tries a tight crop around the X/Y HUD first.
2. Uses wider crops only as fallbacks.
3. Tests the raw crop, grayscale, and high-contrast variants.
4. Upscales each variant before ML Kit OCR.
5. Accepts only an explicit X/Y numeric pair.
6. Normalizes common OCR punctuation/label substitutions without inventing digits.
7. Rejects implausible coordinate values.
8. Keeps the existing safe-failure rule: if no valid pair is obtained, coverage remains paused.

## Simulation / reasoning check

The supplied 1536x707 frames visibly contain the coordinate HUD around the upper-middle of the gameplay area. The previous broad crop included substantially more unrelated UI. V35's first crop is approximately the central 18% of screen width and 12.5% of screen height, centred on the observed HUD position; three wider fallbacks preserve compatibility with small layout/scaling shifts.

Expected examples from the supplied frames include pairs such as:

- `X:227 Y:375`
- `X:251 Y:389`
- `X:257 Y:389`
- `X:267 Y:409`
- `X:300 Y:392`
- `X:312 Y:382`

The parser accepts explicit pairs after normalization and does not accept a lone number, an unrelated player/resource number, or an OCR string without both X and Y labels.

## Important limitation

This is a source-level/static validation, not an on-device ML Kit benchmark. The repository connector cannot execute the Android APK against the uploaded recording. The final acceptance test must therefore be performed on the phone using the supplied recording/game screen.

## Acceptance criteria on phone

- Repeated screenshots at the same map position must report the same X/Y and `SAME VIEWPORT`.
- After a successful map movement, the next screenshot must report the changed X/Y before the sweep is accepted as a new area.
- A transient OCR failure must show `Viewport X/Y not readable` and pause movement rather than guessing.
- No Gather action may be issued merely because OCR or a blue badge was detected; opened-panel verification remains authoritative.
