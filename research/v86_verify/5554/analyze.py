import sys
from PIL import Image

path = sys.argv[1]
y0 = int(sys.argv[2]); y1 = int(sys.argv[3])
dp = float(sys.argv[4])
label = sys.argv[5] if len(sys.argv) > 5 else "row"
thr = float(sys.argv[6]) if len(sys.argv) > 6 else 150.0

im = Image.open(path).convert("RGB")
W, H = im.size
px = im.load()
print(f"{label}: {W}x{H} band y={y0}..{y1} {dp}px/dp thr={thr}")

def dark(x, y):
    r, g, b = px[x, y]
    return (r + g + b) / 3.0 < thr

groups = []
cur = None
for x in range(W):
    cnt = 0; run = 0; mx = 0
    for y in range(y0, y1):
        if dark(x, y):
            cnt += 1; run += 1
            if run > mx: mx = run
        else:
            run = 0
    if cnt > 0:
        if cur and x == cur[1] + 1:
            cur = (cur[0], x, cur[2] + cnt, max(cur[3], mx))
        else:
            if cur: groups.append(cur)
            cur = (x, x, cnt, mx)
    else:
        if cur:
            groups.append(cur); cur = None
if cur: groups.append(cur)

print("  dark-ink column groups:")
for g in groups:
    x0, x1, cnt, mx = g
    w = x1 - x0 + 1
    print(f"   x[{x0}..{x1}] w={w:3d}px={w/dp:6.1f}dp ink={cnt:3d} maxRun={mx:3d}"
          + ("   <== TALL/narrow (divider?)" if (mx > 40 and w <= 4) else ""))
