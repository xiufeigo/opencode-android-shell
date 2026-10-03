# E2ECHK5554_rowscan.py - pixel row scanner for footer/band forensics.
# Usage: python E2ECHK5554_rowscan.py <png> <y0> <y1> [white|dark] [thresh]
# Adapted from research/v95_verify/5560/E2ECHK5560_rowscan.py (device-generic).
import sys
from PIL import Image

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
path, y0, y1 = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
mode = sys.argv[4] if len(sys.argv) > 4 else 'white'
thresh = int(sys.argv[5]) if len(sys.argv) > 5 else 248
im = Image.open(path).convert('RGB')
W, H = im.size
px = im.load()
print(f"FILE {path} size={W}x{H} rows {y0}..{y1} mode={mode} thresh={thresh}")
prev = None
for y in range(y0, y1 + 1):
    if y >= H:
        break
    hit = 0
    for x in range(100, min(980, W), 2):
        r, g, b = px[x, y]
        lum = 0.299 * r + 0.587 * g + 0.114 * b
        if (mode == 'white' and lum > thresh) or (mode == 'dark' and lum < thresh):
            hit += 1
    flag = ''
    if prev is not None and abs(hit - prev) > 150:
        flag = '   <<< TRANSITION'
    print(f"{y:4d} : {hit:4d} {'#' * min(60, hit // 15)}{flag}")
    prev = hit
