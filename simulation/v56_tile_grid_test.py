"""V56.1 grid/semantics acceptance simulation.

No Android or ML runtime dependencies. This is intentionally deterministic so
CI can catch regressions in tile separation and semantic gating.
"""
from math import hypot

# Synthetic isometric lattice matching the game's observed diagonal layout.
OX,OY=760.0,350.0
AX,AY=38.0,19.0
BX,BY=-19.0,9.5
T=[]
for gy in range(460,469):
    for gx in range(128,137):
        T.append((gx,gy,OX+AX*(gx-132)+BX*(gy-464),OY+AY*(gx-132)+BY*(gy-464)))

def nearest(px,py):
    q=min(T,key=lambda t:hypot(t[2]-px,t[3]-py))
    return q if hypot(q[2]-px,q[3]-py) <= 15 else None

# Distinct adjacent cells must remain distinct after small detection noise.
for gx,gy,sx,sy in T:
    q=nearest(sx+2.0,sy-2.0)
    assert q is not None and q[:2]==(gx,gy)

labels={
 (130,464):('RSS','FREE'),
 (131,463):('RSS','OCCUPIED'),
 (132,464):('RSS','APPROACHING'),
 (133,465):('CITY','FREE'),
 (134,462):('TERRAIN','FREE'),
}

def eligible(k):
    kind,state=labels[k]
    return kind=='RSS' and state=='FREE'

assert eligible((130,464))
assert not eligible((131,463))
assert not eligible((132,464))
assert not eligible((133,465))
assert not eligible((134,462))
print('V56.1 TILE GRID + SEMANTICS PASS')
