import sys
from PIL import Image

def topborder(p):
    im = Image.open(p).convert('RGB')
    w, h = im.size
    px = im.load()
    for y in range(0, h - 5):
        # popup border is a light gray rounded rect spanning most of the width
        n = sum(1 for x in range(30, w - 30, 10) if sum(px[x, y]) < 720)
        if n > 60:
            return y
    return -1

for p in sys.argv[1:]:
    print(p, topborder(p))
