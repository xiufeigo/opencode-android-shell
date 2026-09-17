#!/usr/bin/env python3
"""Rasterize the official OpenCode favicon-v3 into legacy-only launcher PNGs.

HyperOS/MIUI has repeatedly mangled adaptive-icon XML (white plate + tiny glyph).
This generator does **not** emit mipmap-anydpi-v26 adaptive XML. Both
android:icon and android:roundIcon are the same full-canvas opaque PNG:
favicon drawn edge-to-edge on #131010, no transparent margins, no 72/108 shrink.
The launcher applies its own squircle/circle mask to the bitmap.
"""
from __future__ import annotations

import shutil
import struct
import zlib
from pathlib import Path

DARK = (0x13, 0x10, 0x10, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)
GRAY = (0x5A, 0x58, 0x58, 0xFF)

# Official favicon-v3.svg on a 512 canvas.
OUTER = (128, 96, 384, 416)
HOLE = (192, 160, 320, 352)
GRAY_PANE = (192, 224, 320, 352)

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "res"
PREVIEW_DIR = ROOT / "frontend" / "branding"
ARTIFACTS = Path("/opt/cursor/artifacts")

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def in_rect(x: float, y: float, rect: tuple[int, int, int, int]) -> bool:
    x0, y0, x1, y1 = rect
    return x0 <= x < x1 and y0 <= y < y1


def sample_512(x: float, y: float) -> tuple[int, int, int, int]:
    if in_rect(x, y, GRAY_PANE):
        return GRAY
    if in_rect(x, y, OUTER) and not in_rect(x, y, HOLE):
        return WHITE
    return DARK


def render(size: int) -> list[tuple[int, int, int, int]]:
    pixels: list[tuple[int, int, int, int]] = []
    to_svg = 512.0 / size
    for py in range(size):
        y = (py + 0.5) * to_svg
        for px in range(size):
            x = (px + 0.5) * to_svg
            pixels.append(sample_512(x, y))
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


def squircle_on_wallpaper(
    size: int,
    src: list[tuple[int, int, int, int]],
    wallpaper: tuple[int, int, int],
    pad: int,
) -> tuple[int, list[tuple[int, int, int, int]]]:
    """Clip the launcher bitmap with a squircle onto a solid wallpaper (HyperOS-style)."""
    canvas = size + pad * 2
    out: list[tuple[int, int, int, int]] = [(wallpaper[0], wallpaper[1], wallpaper[2], 0xFF)] * (canvas * canvas)
    r = size / 2.0 * 0.98
    cx = cy = pad + (size - 1) / 2.0
    p = 4.0
    for py in range(size):
        for px in range(size):
            nx = abs((pad + px) - cx) / r
            ny = abs((pad + py) - cy) / r
            if nx**p + ny**p <= 1.0:
                out[(pad + py) * canvas + (pad + px)] = src[py * size + px]
    return canvas, out


def rm(path: Path) -> None:
    if path.is_file():
        path.unlink()
    elif path.is_dir():
        shutil.rmtree(path)


def main() -> None:
    rm(OUT / "mipmap-anydpi-v26")
    rm(OUT / "drawable-nodpi" / "ic_launcher_background.png")
    for density in DENSITIES:
        rm(OUT / f"mipmap-{density}" / "ic_launcher_foreground.png")
    xml_bg = OUT / "drawable" / "ic_launcher_background.xml"
    rm(xml_bg)

    master = render(512)
    cx, cy = 256, 288
    assert master[cy * 512 + cx][:3] == GRAY[:3], master[cy * 512 + cx]
    for x, y in ((0, 0), (511, 0), (0, 511), (511, 511), (8, 8)):
        c = master[y * 512 + x]
        assert c == DARK, (x, y, c)
        assert c[3] == 0xFF

    for density, size in DENSITIES.items():
        pixels = render(size)
        write_png(OUT / f"mipmap-{density}" / "ic_launcher.png", size, pixels)
        # Identical opaque bitmap — no circular hole HyperOS can fill with a white plate.
        write_png(OUT / f"mipmap-{density}" / "ic_launcher_round.png", size, pixels)

    preview = render(192)
    write_png(PREVIEW_DIR / "launcher-xxxhdpi.png", 192, preview)
    canvas, squircle_white = squircle_on_wallpaper(192, preview, (0xFF, 0xFF, 0xFF), 48)
    write_png(PREVIEW_DIR / "launcher-squircle-white.png", canvas, squircle_white)
    canvas, squircle_sky = squircle_on_wallpaper(192, preview, (0x9E, 0xD8, 0xF7), 48)
    write_png(PREVIEW_DIR / "launcher-squircle-wallpaper.png", canvas, squircle_sky)

    if ARTIFACTS.is_dir():
        write_png(ARTIFACTS / "opencode-launcher-xxxhdpi-1.3.7.png", 192, preview)
        write_png(ARTIFACTS / "opencode-launcher-squircle-white-1.3.7.png", canvas, squircle_white)
        write_png(ARTIFACTS / "opencode-launcher-squircle-wallpaper-1.3.7.png", canvas, squircle_sky)

    print("Wrote legacy-only full-bleed launcher PNGs under", OUT)
    print("Removed mipmap-anydpi-v26 adaptive XML")


if __name__ == "__main__":
    main()
