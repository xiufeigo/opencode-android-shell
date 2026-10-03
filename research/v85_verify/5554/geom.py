"""Pixel-geometry probe for v85 chrome-Ime verification on emulator-5554.

Reports image size, per-row background bands on sample columns, and candidate
edges (keyboard top, composer box, header row, shell tab strip).
"""
import sys
import numpy as np
from PIL import Image


def load(path):
    im = Image.open(path).convert("RGB")
    return np.asarray(im).astype(np.int16)


def row_signature(a, x):
    """Per-row color at column x, plus how much of the row matches it (uniformity)."""
    col = a[:, x, :]
    return col


def bands(a, x, tol=3):
    """Collapse column x into runs of near-constant color."""
    col = a[:, x, :]
    out = []
    start = 0
    for y in range(1, col.shape[0]):
        if np.abs(col[y] - col[start]).max() > tol:
            if y - start >= 2:
                out.append((start, y - 1, tuple(int(v) for v in col[(start + y - 1) // 2])))
            start = y
    if col.shape[0] - start >= 2:
        out.append((start, col.shape[0] - 1, tuple(int(v) for v in col[(start + col.shape[0] - 1) // 2])))
    return out


def ink_rows(a, y0, y1, thr=235):
    """Rows in [y0,y1] that contain dark-ish (text/icon) pixels anywhere across the width."""
    sub = a[y0:y1 + 1]
    dark = (sub.min(axis=2) < thr).sum(axis=1)
    rows = [y0 + i for i, c in enumerate(dark) if c > 0]
    return rows, dark


def clusters(rows, gap=4):
    out = []
    for r in rows:
        if out and r - out[-1][-1] <= gap:
            out[-1].append(r)
        else:
            out.append([r])
    return [(c[0], c[-1], len(c)) for c in out]


def main():
    path = sys.argv[1]
    a = load(path)
    H, W, _ = a.shape
    print(f"FILE {path}")
    print(f"SIZE {W}x{H}")
    cols = [4, 8, 360, W - 8, W - 4]
    for x in cols:
        bs = bands(a, x)
        # only print the taller / more interesting bands
        print(f"\n-- column x={x} bands (y0,y1,color) len>=4 --")
        for y0, y1, c in bs:
            if y1 - y0 + 1 >= 4:
                print(f"   [{y0:4d},{y1:4d}] h={y1-y0+1:4d} {c}")

    print("\n-- ink row clusters (full width, thr=235) --")
    rows, dark = ink_rows(a, 0, H - 1)
    for y0, y1, n in clusters(rows, gap=6):
        print(f"   [{y0:4d},{y1:4d}] h={y1-y0+1:4d} rows={n}  maxdark={int(dark[y0:y1+1].max())}")


if __name__ == "__main__":
    main()
