# V56.5 Release Build

This commit is the consolidated release-build marker. The APK must be built from the current `main` HEAD, not an earlier workflow artifact.

Release intent:
- use the current V54 accessibility service and scanner;
- include the current grid-learning and tile-semantic modules;
- include the current simulation/acceptance fixtures;
- keep AUTO GATHER gated behind authoritative grid calibration and tile-panel verification;
- do not require additional screenshots from the user for build generation.
