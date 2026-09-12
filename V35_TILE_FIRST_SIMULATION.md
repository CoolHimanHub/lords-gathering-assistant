# V35 Tile-First Simulation

## Goal
Validate the proposed V35 inspection pipeline against the supplied gameplay frames before changing the action path.

Pipeline under test:

`map screenshot -> compact level-badge anchor -> local tile/artwork inspection -> fixed-UI obstruction check -> deduplicate -> opened-panel verification -> Gather`

The badge remains an anchor only. It is not treated as proof that a tile is gatherable.

## Replay set

Frames supplied with the project were replayed in observation-only mode:

- `68173.jpg` — 7 compact blue level-badge anchors.
- `68174.jpg` — 4 anchors.
- `68175.jpg` — 7 anchors.

Total: **18 unique visual tile anchors across the three frames**.

## Visual tile inspection

Each anchor was checked against the local artwork immediately up/left of the badge. In the supplied frames, all 18 anchors have visible resource-style artwork behind the badge (ore/stone, wood, food, or similar RSS artwork). No anchor in this replay was classified as a castle/player/monster solely from the local artwork.

This is deliberately an observation result: resource type, occupancy and Gather availability are **not** declared from pixels.

## Fixed-UI safety check

Three anchors are visually present but their ideal probe point is obstructed or too close to fixed UI:

| Frame | Anchors | Visually tile-backed | Safe probe in current layout |
|---|---:|---:|---:|
| 68173 | 7 | 7 | 5 |
| 68174 | 4 | 4 | 4 |
| 68175 | 7 | 7 | 6 |
| **Total** | **18** | **18** | **15** |

The blocked candidates are retained as detections but must not be tapped until a safe point exists. This is preferable to shrinking the map ROI and losing valid lower/edge resources.

## Important regression result

The current V34 source reproduces the compact-badge detector at approximately **7 / 4 / 7** on these three frames. The earlier V32 repository simulation documented **7 / 4 / 8**, while the older installed build screenshots showed much larger candidate lists such as 16. This confirms that the installed APK and current source have not been equivalent detector revisions.

Therefore V35 should not chase the old on-screen candidate count. It should make the candidate lifecycle explicit: **anchor -> tile inspection -> safe probe -> panel verification**.

## Action gate

A candidate is allowed to reach the panel-opening stage only when:

1. the badge anchor is inside the map ROI;
2. the local tile inspection has sufficient artwork evidence;
3. the calculated probe point is outside fixed UI regions;
4. the viewport is new according to X/Y;
5. the candidate has not already been seen in the current global viewport key.

After the tile is opened, `TilePanelVerifier.safeToGather` remains the final gate. A Gather action must not be sent merely because the map detector scored highly.

## What this simulation proves

- The compact badge can reliably provide a useful tile anchor on the supplied frames.
- Local artwork inspection can be used as a second-stage sanity check without pretending to know occupancy/type from map pixels.
- Fixed-UI obstruction must be handled per candidate rather than by reducing the whole map ROI.
- X/Y viewport validation and candidate deduplication remain necessary.

## What this simulation does NOT prove

It does not prove that an individual tile is currently unoccupied or that its opened panel exposes a Gather button. Those require the live opened-panel verification stage.

## V35 implementation decision

Proceed with a **tile-inspection stage and safe-probe calculation**, while preserving the existing X/Y coverage gate and panel-authoritative Gather gate. No blind Gather action is enabled by this simulation alone.
