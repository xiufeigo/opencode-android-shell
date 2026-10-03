import sys
from PIL import Image

path = sys.argv[1]
y0 = int(sys.argv[2]); y1 = int(sys.argv[3])
x0 = int(sys.argv[4]); x1 = int(sys.argv[5])
dp = float(sys.argv[6])
thr = float(sys.argv[7]) if len(sys.argv) > 7 else 215.0

im = Image.open(path).convert("RGB")
px = im.load()
W, H = im.size
print(f"scan x[{x0}..{x1}] y[{y0}..{y1}] thr={thr} {dp}px/dp")
for x in range(x0, x1 + 1):
    cnt = 0; run = 0; mx = 0; ys = []
    for y in range(y0, y1):
        r, g, b = px[x, y]
        if (r + g + b) / 3.0 < thr:
            cnt += 1; run += 1; ys.append(y)
            if run > mx: mx = run
        else:
            run = 0
    if cnt:
        print(f"  x={x:3d} ink={cnt:3d} maxRun={mx:3d} yspan={ys[0]}..{ys[-1]}")
