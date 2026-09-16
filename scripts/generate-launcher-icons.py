#!/usr/bin/env python3
"""Rasterize the official OpenCode favicon-v3 mark into Android mipmap PNGs.

HyperOS content-crops a uniform dark field off adaptive foregrounds and then
sits the remaining window on a white plate. Both adaptive layers are therefore
the same opaque full-bleed mark (PNG background, not a color-only shape), and
the dark field is slightly dithered so the crop bbox stays the full canvas.
"""
from __future__ import annotations

import struct
import zlib
from pathlib import Path

BG = (0x13, 0x10, 0x10, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)
GRAY = (0x5A, 0x58, 0x58, 0xFF)
TRANSPARENT = (0x00, 0x00, 0x00, 0x00)

# Coordinates in the 512×512 official SVG viewport.
OUTER = (128, 96, 384, 416)
HOLE = (192, 160, 320, 352)
GRAY_PANE = (192, 224, 320, 352)

# Window ~80% of the canvas so the 66% adaptive safe-zone is filled with the
# mark after OEM masks; dark still reaches the bitmap edges.
SCALE = 1.58
CX = CY = 256.0

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "res"
PREVIEW_DIR = ROOT / "frontend" / "branding"


def in_rect(x: float, y: float, rect: tuple[int, int, int, int]) -> bool:
    x0, y0, x1, y1 = rect
    return x0 <= x < x1 and y0 <= y < y1


def dither_dark(px: int, py: int) -> tuple[int, int, int, int]:
    # ±2 RGB, still reads as #131010. Breaks HyperOS "empty plate" detection.
    n = (px * 3 + py * 7) & 3
    v = 0x13 + n
    return (v, max(0x10, v - 3), max(0x10, v - 3), 0xFF)


def sample_512(x: float, y: float, px: int, py: int) -> tuple[int, int, int, int]:
    sx = CX + (x - CX) / SCALE
    sy = CY + (y - CY) / SCALE
    if not (0 <= sx < 512 and 0 <= sy < 512):
        return dither_dark(px, py)
    if in_rect(sx, sy, GRAY_PANE):
        return GRAY
    if in_rect(sx, sy, OUTER) and not in_rect(sx, sy, HOLE):
        return WHITE
    return dither_dark(px, py)


def render(size: int, round_mask: bool = False) -> list[tuple[int, int, int, int]]:
    pixels: list[tuple[int, int, int, int]] = []
    scale = 512.0 / size
    r = (size - 1) / 2.0
    r2 = r * r
    for py in range(size):
        y = (py + 0.5) * scale
        for px in range(size):
            x = (px + 0.5) * scale
            pixel = sample_512(x, y, px, py)
            if round_mask:
                dx = px - r
                dy = py - r
                if dx * dx + dy * dy > r2:
                    pixel = TRANSPARENT
            pixels.append(pixel)
    return pixels


def write_png(path: Path, size: int, pixels: list[tuple[int, int, int, int]]) -> None:
    raw = bytearray()
    i = 0
    for _y in range(size):
        raw.append(0)
        for _x in range(size):
            r, g, b, a = pixels[i]
            raw.extend((r, g, b, a))
            i += 1
    compressed = zlib.compress(bytes(raw), 9)

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
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
        square = render(size, round_mask=False)
        round_px = render(size, round_mask=True)
        write_png(OUT / f"mipmap-{density}" / "ic_launcher.png", size, square)
        write_png(OUT / f"mipmap-{density}" / "ic_launcher_round.png", size, round_px)

    for density, size in foreground.items():
        pixels = render(size, round_mask=False)
        write_png(OUT / f"mipmap-{density}" / "ic_launcher_foreground.png", size, pixels)

    # Adaptive background must be a bitmap. HyperOS ignores color-only shapes.
    bg = OUT / "drawable-nodpi"
    write_png(bg / "ic_launcher_background.png", 432, render(432, round_mask=False))
    xml_bg = OUT / "drawable" / "ic_launcher_background.xml"
    if xml_bg.exists():
        xml_bg.unlink()

    preview = render(192, round_mask=False)
    write_png(PREVIEW_DIR / "launcher-xxxhdpi.png", 192, preview)
    write_png(PREVIEW_DIR / "launcher-foreground-xxxhdpi.png", 432, render(432, round_mask=False))
    artifacts = Path("/opt/cursor/artifacts")
    if artifacts.is_dir():
        write_png(artifacts / "opencode-launcher-xxxhdpi.png", 192, preview)
        write_png(artifacts / "opencode-launcher-foreground-xxxhdpi.png", 432, render(432, round_mask=False))

    master = render(512)
    cx, cy = 256, 288
    assert master[cy * 512 + cx][:3] == GRAY[:3], master[cy * 512 + cx]
    corner = master[8 * 512 + 8]
    assert corner[0] < 40 and corner[1] < 40 and corner[2] < 40, corner
    print("Wrote full-bleed launcher PNGs under", OUT)


if __name__ == "__main__":
    main()
