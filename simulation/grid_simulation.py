"""V55.2 deterministic grid simulation.

Runs entirely offline against synthetic isometric maps. It validates the
online GridLearningEngine math before any phone tap is allowed.
"""
import math, random

random.seed(48)
W,H=1536,707
x0,y0=760,350
DX,DY=38.0,-19.0
EX,EY=19.0,9.5
samples=[]
for gy in range(430,481,5):
    for gx in range(110,151,5):
        sx=x0+DX*(gx-130)+DY*(gy-455)+random.uniform(-2,2)
        sy=y0+EX*(gx-130)+EY*(gy-455)+random.uniform(-2,2)
        samples.append((gx,gy,sx,sy))

# Closed-form 2D affine fit, matching the Android learner's model.
def fit(rows):
    mx=sum(r[0] for r in rows)/len(rows); my=sum(r[1] for r in rows)/len(rows)
    msx=sum(r[2] for r in rows)/len(rows); msy=sum(r[3] for r in rows)/len(rows)
    xx=sum((r[0]-mx)**2 for r in rows); yy=sum((r[1]-my)**2 for r in rows)
    ax=sum((r[0]-mx)*(r[2]-msx) for r in rows)/xx
    bx=sum((r[1]-my)*(r[2]-msx) for r in rows)/yy
    ay=sum((r[0]-mx)*(r[3]-msy) for r in rows)/xx
    by=sum((r[1]-my)*(r[3]-msy) for r in rows)/yy
    cx=msx-ax*mx-bx*my; cy=msy-ay*mx-by*my
    errs=[]
    for gx,gy,sx,sy in rows:
        px=ax*gx+bx*gy+cx; py=ay*gx+by*gy+cy
        errs.append(math.hypot(px-sx,py-sy))
    return (ax,bx,cx,ay,by,cy,sorted(errs)[len(errs)//2])

m=fit(samples)
print('samples=',len(samples))
print('median_residual_px=',round(m[-1],3))
print('LOCK=',m[-1] <= 14 and len(samples)>=10)
assert m[-1] <= 14, 'grid simulation failed residual threshold'
assert len(samples) >= 10

# Viewport shift simulation: same grid, translated screen coordinates.
shifted=[(gx,gy,sx+210,sy-74) for gx,gy,sx,sy in samples]
m2=fit(shifted)
print('viewport_shift_residual_px=',round(m2[-1],3))
assert m2[-1] <= 14
print('SIMULATION PASS')
