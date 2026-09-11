# Vision Simulation V32

## Purpose
Validate the badge-first detector against the supplied 1536x707 gameplay frames before relying on it for candidate probing.

## Offline replay
The V32 pixel rules were replayed against the supplied frames `68173.jpg`, `68174.jpg`, and `68175.jpg` in observation-only mode. No game taps or Gather actions were performed.

| Frame | V32 candidate count | Result |
|---|---:|---|
| 68173.jpg | 7 | Compact blue level badges recovered across grass/snow/dark terrain; known top-left Transformer false-positive zone rejected. |
| 68174.jpg | 4 | Compact blue level badges recovered on dark terrain; top-left header/Transformer false-positive rejected. |
| 68175.jpg | 8 | Upper, middle and lower-map badges recovered, including the lower resource badge that V31 could lose because of its ROI boundary. |

## What changed from V31

1. **3x3 connectivity bridge** — white/anti-aliased level digits can split one blue badge into multiple components. V32 bridges those one-pixel gaps without treating the bridge pixels as confidence evidence.
2. **Wider vertical ROI** — the detector now inspects down to `height - 42` instead of stopping 110 pixels above the bottom, so lower-map RSS badges remain candidates.
3. **Fixed UI exclusions** — assistant overlay and right-side fixed controls remain outside the candidate region; a narrow top-left Transformer/header false-positive zone is rejected.
4. **Original-pixel confidence** — candidate density and glyph evidence are calculated from the original blue pixels rather than the connectivity bridge.
5. **Probe offset retained** — the final probe is shifted from the badge toward the resource artwork, while clamped away from the assistant overlay.

## Safety gate
V32 continues to return `RSS?` candidates only. Resource type, level, occupancy and Gather availability are not inferred from map artwork. The opened tile panel remains authoritative; `TilePanelVerifier.safeToGather` must pass before the service can send the final Gather action.

## Important interpretation
The counts above are **candidate counts**, not proof that every candidate is gatherable. A candidate can still be a monster/castle/occupied tile or another non-RSS object. That is intentional: candidate detection favors recall, while the opened-panel verification gate controls final action.
