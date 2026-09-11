# V30 Vision Simulation Report

## Source frames

The detector was simulated against the two supplied 1536x707 gameplay screenshots from the current conversation.

## Result

| Frame | Previous V29 | V30 | Result |
|---|---:|---:|---|
| 68147.jpg | 9 candidates | 8 candidates | Pass |
| 68146.jpg | 9 candidates | 8 candidates | Pass |

## What changed

V29 admitted a 31x31 blue connected component from the Transformers artwork/icon as a false RSS badge. The genuine level badges in the supplied frames are compact trapezoids, approximately 20–40 px wide and 16–28 px high.

V30 tightens the badge geometry and area limits and starts scanning just outside the assistant overlay. The eight visible RSS level badges remain detected while the Transformers false positive is rejected.

## Probe validation

For every retained badge, the proposed tap point is shifted approximately 18 px left and 7 px up from the badge centre. In the supplied frames this lands on the resource artwork rather than on the blue level-number badge.

## Action policy

The detector does **not** infer resource type, level, occupancy, or gatherability from map artwork. It only nominates a candidate. Auto Gather must still open one candidate panel, read/verify the panel, and only then press Gather. It does not click every tile.

## Build

The V30 detector change is committed to `main`, which automatically triggered the Android APK GitHub Actions build.
