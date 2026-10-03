"""Objective chrome-presence detector for v85 verification.

For each screenshot, reports whether the header row and the view tab bar are
present in the app area (above the keyboard), plus the keyboard top edge.
Tab-bar detection keys on the blue selected-tab underline, which is a stable
signature across densities.
"""
import sys
import numpy as np
from PIL import Image

KB_BG = np.array([238, 237, 246])   # Gboard panel
TOL = 6


def analyse(path):
    a = np.asarray(Image.open(path).convert("RGB")).astype(int)
    H, W, _ = a.shape
    kb = np.abs(a - KB_BG).max(axis=2) <= TOL
    # Keyboard top = topmost row that is an almost full-width keyboard band.
    # (Scanning from the bottom fails because white key faces break the run.)
    ktop = None
    for y in range(H):
        if kb[y].sum() > W * 0.85:
            ktop = y
            break
    app_bottom = (ktop - 1) if ktop else H - 1

    sub = a[:app_bottom + 1]
    dark = (sub.min(axis=2) < 200)
    # blue underline: strongly blue pixels
    r, g, b = sub[:, :, 0], sub[:, :, 1], sub[:, :, 2]
    blue = (b > 150) & (b - r > 55) & (b - g > 35)

    def rowhas(mask, minpx=12):
        idx = [y for y in range(mask.shape[0]) if mask[y].sum() >= minpx]
        return idx

    blue_rows = rowhas(blue, 12)
    ink_rows = [y for y in range(sub.shape[0]) if dark[y].sum() >= 3]
    return dict(path=path, W=W, H=H, ktop=ktop, app_bottom=app_bottom,
                blue_rows=blue_rows, ink_rows=ink_rows)


def clusters(rows, gap=5):
    out = []
    for r in rows:
        if out and r - out[-1][-1] <= gap:
            out[-1].append(r)
        else:
            out.append([r])
    return [(c[0], c[-1]) for c in out]


for p in sys.argv[1:]:
    r = analyse(p)
    name = p.rsplit("\\", 1)[-1].rsplit("/", 1)[-1]
    print(f"\n=== {name}  ({r['W']}x{r['H']}) ===")
    if r['ktop']:
        print(f"  keyboard top   = {r['ktop']}   (keyboard h = {r['H'] - r['ktop']}px)")
    else:
        print("  keyboard top   = none (IME down)")
    print(f"  app visible    = 0..{r['app_bottom']}")
    bc = clusters(r['blue_rows'], gap=8)
    print(f"  blue underline rows (tab bar marker) = {bc if bc else 'NONE -> tab bar ABSENT'}")
    ic = clusters(r['ink_rows'], gap=6)
    print("  ink clusters    = " + ", ".join(f"{a}-{b}" for a, b in ic))
