# E2ECHK5558_edgeprobe.py - find the popup card's left/right edge in a screenshot by
# scanning one horizontal line for the card background vs. the page background.
# Usage: python E2ECHK5558_edgeprobe.py <png> <y>
import sys
from PIL import Image

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

path, y = sys.argv[1], int(sys.argv[2])
im = Image.open(path).convert('RGB')
w, h = im.size
px = im.load()
row = [px[x, y] for x in range(w)]


def is_page(c):
    # light grey page background, no card
    return abs(c[0] - c[1]) < 6 and abs(c[1] - c[2]) < 6 and 235 <= c[0] <= 250


def is_card(c):
    # white / near-white card surface
    return c[0] >= 250 and c[1] >= 250 and c[2] >= 250


runs = []
cur = None
for x in range(w):
    flag = is_card(row[x])
    if cur is None or cur[0] != flag:
        if cur is not None:
            runs.append((cur[0], cur[1], x - 1))
        cur = (flag, x)
runs.append((cur[0], cur[1], w - 1))

print(f"image {w}x{h}  y={y}  rgb(0)={row[0]} rgb(700)={row[700] if w > 700 else '-'}")
for flag, a, b in runs:
    if b - a >= 6:
        kind = 'CARD' if flag else 'page'
        print(f"  {kind:5s} x={a}..{b}  width={b - a + 1}")
