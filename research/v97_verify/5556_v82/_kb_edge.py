import sys
from PIL import Image
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
im = Image.open(sys.argv[1]).convert("RGB")
x = int(sys.argv[2]); y0=int(sys.argv[3]); y1=int(sys.argv[4])
prev=None
for y in range(y0,y1):
    p = im.getpixel((x,y))
    if p != prev:
        print(f"  y={y}: {p}"); prev = p
