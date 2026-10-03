import sys
from PIL import Image

path = sys.argv[1]
y = int(sys.argv[2])
dp = float(sys.argv[3])
label = sys.argv[4]

im = Image.open(path).convert("RGB")
px = im.load()
W, H = im.size
print(f"{label}: scanline y={y} ({dp}px/dp)")
prev = None
for x in range(W):
    c = px[x, y]
    if c != prev:
        print(f"  x={x:3d} ({(x)/dp:6.1f}dp)  rgb={c}")
        prev = c
