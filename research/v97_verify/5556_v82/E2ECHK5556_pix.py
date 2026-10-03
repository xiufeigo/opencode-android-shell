# E2ECHK5556_pix.py - pixel probe for the Settings card edges.
#
# The v82 diagnosis in HANDOFF:71 used a PIXEL probe ("40_set.png and 20_sc.png
# card top edge are both y=84"), so this repeats that method: scan a vertical
# column and report where the card's BRIGHT WHITE surface starts and stops.
# a11y bounds of a Compose node that carries imePadding can report the padded
# OUTER box, so the painted edge is the trustworthy reading here.
#
# Usage: python E2ECHK5556_pix.py <png> [x1,x2,...]
import sys
from PIL import Image

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

path = sys.argv[1]
cols = [int(v) for v in sys.argv[2].split(',')] if len(sys.argv) > 2 else [196, 200, 1160, 1165]
im = Image.open(path).convert('RGB')
W, H = im.size
print(f"file={path}  size={W}x{H}")

for x in cols:
    runs = []
    in_white = False
    start = 0
    for y in range(H):
        r, g, b = im.getpixel((x, y))
        # card surface is near-white and neutral; the dimmed scrim behind is
        # mid-grey, the Gboard background is slightly cool grey (#e8eaed-ish).
        white = (r >= 246 and g >= 246 and b >= 246)
        if white and not in_white:
            in_white, start = True, y
        elif not white and in_white:
            in_white = False
            if y - start >= 4:
                runs.append((start, y - 1, y - start))
    if in_white and H - start >= 4:
        runs.append((start, H - 1, H - start))
    print(f"  x={x}: white runs (>=4px) = " +
          ", ".join(f"y{a}..{b}(h{c})" for a, b, c in runs))
