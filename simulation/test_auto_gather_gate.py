from pathlib import Path

# Keep this simulation dependency-free so GitHub Actions can execute it fast.
class Gate:
    def __init__(self,min_samples=10,max_residual=14.0):
        self.min_samples=min_samples; self.max_residual=max_residual
    def locked(self,n,r): return n>=self.min_samples and r<=self.max_residual
    def authorize(self,enabled,n,r,panel,target):
        return enabled and self.locked(n,r) and panel and target

g=Gate()
cases=[
    (False,20,5,True,True,False),
    (True,9,5,True,True,False),
    (True,20,15,True,True,False),
    (True,20,5,False,True,False),
    (True,20,5,True,False,False),
    (True,20,5,True,True,True),
]
for args in cases:
    got=g.authorize(*args); expected=args[0] and args[1]>=10 and args[2]<=14 and args[3] and args[4]
    assert got==expected,(args,got,expected)
print('AUTO GATHER GATE SIMULATION PASS')
