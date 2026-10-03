"""Measure a dialog card's top edge by scanning a vertical pixel column.

The card paints an opaque near-white surface over a darker scrim, so the first
row whose colour is 'card-like' (bright AND low saturation) is the card's top
edge. Scans several columns and reports the median so a text glyph or a border
cannot skew the reading.
"""
import sys
from PIL import Image


def card_top(path, columns=(400, 900, 1280, 1700, 2200), verbose=False):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    readings = []
    for x in columns:
        if x >= w:
            continue
        edge = None
        for y in range(0, h):
            r, g, b = px[x, y]
            mx, mn = max(r, g, b), min(r, g, b)
            bright = mx >= 235
            neutral = (mx - mn) <= 12
            if bright and neutral:
                edge = y
                break
        if edge is not None:
            readings.append((x, edge))
    if not readings:
        return None
    vals = sorted(e for _, e in readings)
    median = vals[len(vals) // 2]
    if verbose:
        print(f"  {path}: per-column {readings} -> median y={median}")
    return median


if __name__ == "__main__":
    for p in sys.argv[1:]:
        print(p, "->", card_top(p, verbose=True))
