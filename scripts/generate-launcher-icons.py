#!/usr/bin/env python3
"""Rasterize the official OpenCode favicon-v3 mark into Android mipmap PNGs.

The SVG (packages/app/public/favicon-v3.svg) is only axis-aligned rectangles, so
this writes lossless PNGs with the stdlib — no SVG→PNG converter required.
"""
from __future__ import annotations

import struct
import zlib
from pathlib import Path

BG = (0x13, 0x10, 0x10)
WHITE = (0xFF, 0xFF, 0xFF)
GRAY = (0x5A, 0x58, 0x58)

# Coordinates in the 512×512 official SVG viewport.
OUTER = (128, 96, 384, 416)  # white window chrome
HOLE = (192, 160, 320, 352)  # inner cutout
GRAY_PANE = (192, 224, 320, 352)

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "res"


def in_rect(x: float, y: float, rect: tuple[int, int, int, int]) -> bool:
    x0, y0, x1, y1 = rect
    return x0 <= x < x1 and y0 <= y < y1


def sample_512(x: float, y: float) -> tuple[int, int, int]:
    if in_rect(x, y, GRAY_PANE):
        return GRAY
    if in_rect(x, y, OUTER) and not in_rect(x, y, HOLE):
        return WHITE
    return BG


def render(size: int) -> list[tuple[int, int, int]]:
    pixels: list[tuple[int, int, int]] = []
    scale = 512.0 / size
    for py in range(size):
        y = (py + 0.5) * scale
        for px in range(size):
            x = (px + 0.5) * scale
            pixels.append(sample_512(x, y))
    return pixels


def write_png(path: Path, size: int, pixels: list[tuple[int, int, int]]) -> None:
    raw = bytearray()
    i = 0
    for _y in range(size):
        raw.append(0)
        for _x in range(size):
            r, g, b = pixels[i]
            raw.extend((r, g, b))
            i += 1
    compressed = zlib.compress(bytes(raw), 9)

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", compressed) + chunk(b"IEND", b""))


def main() -> None:
    launcher = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    foreground = {
        "mdpi": 108,
        "hdpi": 162,
        "xhdpi": 216,
        "xxhdpi": 324,
        "xxxhdpi": 432,
    }

    for density, size in launcher.items():
        pixels = render(size)
        write_png(OUT / f"mipmap-{density}" / "ic_launcher.png", size, pixels)
        write_png(OUT / f"mipmap-{density}" / "ic_launcher_round.png", size, pixels)

    for density, size in foreground.items():
        pixels = render(size)
        write_png(OUT / f"mipmap-{density}" / "ic_launcher_foreground.png", size, pixels)

    # Spot-check the official geometry on a 512 master.
    master = render(512)
    cx, cy = 256, 288  # gray pane
    assert master[cy * 512 + cx] == GRAY, master[cy * 512 + cx]
    assert master[120 * 512 + 256] == WHITE  # top chrome
    assert master[10 * 512 + 10] == BG
    print("Wrote launcher + adaptive-foreground PNGs under", OUT)


if __name__ == "__main__":
    main()
