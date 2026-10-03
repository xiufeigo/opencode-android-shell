import sys
from PIL import Image

path = sys.argv[1]
x = int(sys.argv[2])
dp = float(sys.argv[3])
label = sys.argv[4]
y0 = int(sys.argv[5]) if len(sys.argv) > 5 else 0
y1 = int(sys.argv[6]) if len(sys.argv) > 6 else 1280

im = Image.open(path).convert("RGB")
px = im.load()
W, H = im.size
print(f"{label}: vertical scan x={x} ({x/dp:.1f}dp)")
prev = None
for y in range(y0, min(y1, H)):
    c = px[x, y]
    key = 'DARK' if sum(c)/3 < 140 else ('MID' if sum(c)/3 < 230 else 'BG')
    if key != prev:
        print(f"  y={y:4d} ({y/dp:6.1f}dp)  -> {key}  rgb={c}")
        prev = key
