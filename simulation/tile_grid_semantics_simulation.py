"""Offline simulation for the learned tile-grid/semantic pipeline.

The test models an isometric lattice and different tile occupants. It checks
that position and semantics stay separate: two adjacent RSS tiles remain two
distinct tiles, while non-RSS objects never become gather targets.
"""
from math import hypot

TILES=[]
for y in range(460,467):
    for x in range(130,135):
        sx=760+38*(x-132)-19*(y-464)
        sy=350+19*(x-132)+9.5*(y-464)
        TILES.append((x,y,sx,sy))

labels={
    (130,464):("RSS","GOLD","FREE"),
    (131,463):("RSS","TIMBER","FREE"),
    (132,464):("RSS","ORE","OCCUPIED"),
    (133,465):("RSS","STONE","APPROACHING"),
    (134,462):("TERRAIN","UNKNOWN","FREE"),
}

# Cell assignment: nearest lattice coordinate wins only when distance is
# comfortably below half a tile spacing. This prevents adjacent-tile merging.
def nearest(px,py):
    best=min(TILES,key=lambda t:hypot(t[2]-px,t[3]-py))
    d=hypot(best[2]-px,best[3]-py)
    return best if d < 18 else None

for key,(kind,res,occ) in labels.items():
    t=next(t for t in TILES if t[:2]==key)
    got=nearest(t[2]+1.5,t[3]-1.0)
    assert got and got[:2]==key

# Semantic gate: only free RSS may become a gather target.
def can_gather(label):
    kind,res,occ=label
    return kind=="RSS" and occ=="FREE"

assert can_gather(labels[(130,464)])
assert can_gather(labels[(131,463)])
assert not can_gather(labels[(132,464)])
assert not can_gather(labels[(133,465)])
assert not can_gather(labels[(134,462)])
print("GRID_SEMANTICS_SIMULATION PASS")
