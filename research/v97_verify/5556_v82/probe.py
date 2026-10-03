# -*- coding: utf-8 -*-
"""Pixel probe helper for v97 task-5 (emulator-5556, 1366x768@160dpi).
Usage:
  python probe.py crop <img> <x1> <y1> <x2> <y2> <scale> <out>
  python probe.py colscan <img> <x> [y1] [y2]   # print rows where luminance jumps along column x
  python probe.py rowscan <img> <y> [x1] [x2]   # print cols where luminance jumps along row y
"""
import sys
from PIL import Image
import numpy as np

def lum(a):
    return 0.299*a[...,0] + 0.587*a[...,1] + 0.114*a[...,2]

def main():
    cmd = sys.argv[1]
    if cmd == "crop":
        img = sys.argv[2]
        x1, y1, x2, y2, scale = (int(v) for v in sys.argv[3:8])
        out = sys.argv[8]
        im = Image.open(img).convert("RGB").crop((x1, y1, x2, y2))
        im = im.resize((im.width*scale, im.height*scale), Image.NEAREST)
        im.save(out)
        print("saved", out, im.size)
    elif cmd == "colscan":
        img = sys.argv[2]; x = int(sys.argv[3])
        y1 = int(sys.argv[4]) if len(sys.argv) > 4 else 0
        y2 = int(sys.argv[5]) if len(sys.argv) > 5 else None
        a = np.asarray(Image.open(img).convert("RGB"), dtype=np.float32)
        col = lum(a)[y1:(y2 or a.shape[0]), x]
        prev = col[0]; start = y1
        print(f"colscan x={x} from y={y1}: start lum={prev:.0f}")
        for i in range(1, len(col)):
            if abs(col[i] - prev) > 18:
                print(f"  y={y1+i}: lum {prev:.0f} -> {col[i]:.0f}")
            prev = col[i]
    elif cmd == "rowscan":
        img = sys.argv[2]; y = int(sys.argv[3])
        x1 = int(sys.argv[4]) if len(sys.argv) > 4 else 0
        x2 = int(sys.argv[5]) if len(sys.argv) > 5 else None
        a = np.asarray(Image.open(img).convert("RGB"), dtype=np.float32)
        row = lum(a)[y, x1:(x2 or a.shape[1])]
        prev = row[0]
        print(f"rowscan y={y} from x={x1}: start lum={prev:.0f}")
        for i in range(1, len(row)):
            if abs(row[i] - prev) > 18:
                print(f"  x={x1+i}: lum {prev:.0f} -> {row[i]:.0f}")
            prev = row[i]

if __name__ == "__main__":
    main()
