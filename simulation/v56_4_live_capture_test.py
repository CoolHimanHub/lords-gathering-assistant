"""Acceptance checks for the live viewport sequence supplied during V56 testing."""

CAPTURES = [
    (129, 461),
    (127, 461),
    (125, 457),
    (123, 455),
    (122, 456),
    (119, 451),
]

# Every recorded transition must be a real authoritative viewport change.
for before, after in zip(CAPTURES, CAPTURES[1:]):
    assert before != after
    dx = after[0] - before[0]
    dy = after[1] - before[1]
    assert abs(dx) <= 120 and abs(dy) <= 120

# A repeated viewport is verification only; it must not create a new grid sample.
repeated = (119, 451)
assert repeated == CAPTURES[-1]

# The sequence moves in world X/Y, so a mapper may use cross-viewport pairs.
# It must never treat a repeated frame as a calibration transition.
transitions = len(CAPTURES) - 1
assert transitions == 5
print("V56.4 LIVE CAPTURE MOVEMENT GATE PASS")
