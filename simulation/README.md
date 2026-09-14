# V55.3 Simulation Gate

The grid simulation is a mandatory precondition for enabling automatic target taps.

Run:

```bash
python3 simulation/grid_simulation.py
```

Expected output ends with `SIMULATION PASS`.

Runtime policy:
- simulation validates the grid learner offline;
- runtime still requires live grid calibration lock;
- tile-panel verification remains the final target truth check;
- no automatic Gather action is permitted while the grid is unlocked.
