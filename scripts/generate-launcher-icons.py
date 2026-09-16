#!/usr/bin/env python3
"""Rasterize the official OpenCode favicon-v3 mark into Android mipmap PNGs.

Official SVG (512×512): full-bleed #131010, white window (128,96)–(384,416),
gray pane in the lower hole.

Two mappings:
  * Legacy ic_launcher (48–192dp): SCALE=1 so the square PNG *is* the favicon.
    Launchers that ignore adaptive (or HyperOS after uninstall) show this.
  * Adaptive FG/BG (108–432dp): SCALE=72/108 so the *entire* favicon, including
    its dark margin, sits in the 66% safe zone. SCALE=1 on 108dp lets the OEM
    mask eat the dark margin and leaves a white plate + tiny hole.

HyperOS/MIUI ignores a color-only <shape> background and content-crops a
*uniform* dark field onto a white plate. Both adaptive layers are the same
opaque PNG; the dark field is Bayer-dithered. Never reference @mipmap/ic_launcher
from the adaptive XML (API 26+ that is the XML itself → Android robot).
"""
from __future__ import annotations

import struct
import zlib
from pathlib import Path

BG_ALT = (0x1E, 0x18, 0x18, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)
GRAY = (0x5A, 0x58, 0x58, 0xFF)
TRANSPARENT = (0x00, 0x00, 0x00, 0x00)

OUTER = (128, 96, 384, 416)
HOLE = (192, 160, 320, 352)
GRAY_PANE = (192, 224, 320, 352)

LEGACY_SCALE = 1.0
# Map the full 512 SVG onto the 72dp safe zone of a 108dp adaptive canvas.
ADAPTIVE_SCALE = 72.0 / 108.0
CX = CY = 256.0

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "res"
PREVIEW_DIR = ROOT / "frontend" / "branding"


def in_rect(x: float, y: float, rect: tuple[int, int, int, int]) -> bool:
    x0, y0, x1, y1 = rect
    return x0 <= x < x1 and y0 <= y < y1


def dither_dark(px: int, py: int) -> tuple[int, int, int, int]:
    bayer = (px & 1) ^ ((py >> 1) & 1)
    n = (px * 13 + py * 31) & 7
    if bayer:
        return (min(0x28, 0x13 + 6 + (n >> 1)), 0x14 + (n >> 2), 0x14, 0xFF)
    return (0x13 + (n >> 2), 0x10, 0x10, 0xFF)


def sample_512(x: float, y: float, px: int, py: int, scale: float) -> tuple[int, int, int, int]:
    sx = CX + (x - CX) / scale
    sy = CY + (y - CY) / scale
    if not (0 <= sx < 512 and 0 <= sy < 512):
        return dither_dark(px, py)
    if in_rect(sx, sy, GRAY_PANE):
        return GRAY
    if in_rect(sx, sy, OUTER) and not in_rect(sx, sy, HOLE):
        return WHITE
    return dither_dark(px, py)


def render(size: int, scale: float, round_mask: bool = False) -> list[tuple[int, int, int, int]]:
    pixels: list[tuple[int, int, int, int]] = []
    to_svg = 512.0 / size
    r = (size - 1) / 2.0
    r2 = r * r
    edge = max(1, size // 128)
    for py in range(size):
        y = (py + 0.5) * to_svg
        for px in range(size):
            x = (px + 0.5) * to_svg
            pixel = sample_512(x, y, px, py, scale)
            if px < edge or py < edge or px >= size - edge or py >= size - edge:
                if pixel[0] < 40:
                    pixel = BG_ALT
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


def squircle_preview(size: int, src: list[tuple[int, int, int, int]]) -> list[tuple[int, int, int, int]]:
    """Simulate a 72/108 adaptive mask on a light checker."""
    out: list[tuple[int, int, int, int]] = []
    r = size * (72.0 / 108.0) / 2.0
    cx = cy = (size - 1) / 2.0
    p = 4.0
    for py in range(size):
        for px in range(size):
            nx = abs(px - cx) / r
            ny = abs(py - cy) / r
            if nx**p + ny**p <= 1.0:
                out.append(src[py * size + px])
            else:
                g = 0xE8 if ((px // 8) + (py // 8)) & 1 else 0xF4
                out.append((g, g, g, 0xFF))
    return out


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
        square = render(size, LEGACY_SCALE, round_mask=False)
        round_px = render(size, LEGACY_SCALE, round_mask=True)
        write_png(OUT / f"mipmap-{density}" / "ic_launcher.png", size, square)
        write_png(OUT / f"mipmap-{density}" / "ic_launcher_round.png", size, round_px)

    for density, size in foreground.items():
        pixels = render(size, ADAPTIVE_SCALE, round_mask=False)
        write_png(OUT / f"mipmap-{density}" / "ic_launcher_foreground.png", size, pixels)

    bg = OUT / "drawable-nodpi"
    write_png(bg / "ic_launcher_background.png", 432, render(432, ADAPTIVE_SCALE, round_mask=False))
    xml_bg = OUT / "drawable" / "ic_launcher_background.xml"
    if xml_bg.exists():
        xml_bg.unlink()

    legacy_preview = render(192, LEGACY_SCALE, round_mask=False)
    adaptive_preview = render(192, ADAPTIVE_SCALE, round_mask=False)
    fg = render(432, ADAPTIVE_SCALE, round_mask=False)
    write_png(PREVIEW_DIR / "launcher-xxxhdpi.png", 192, legacy_preview)
    write_png(PREVIEW_DIR / "launcher-foreground-xxxhdpi.png", 432, fg)
    artifacts = Path("/opt/cursor/artifacts")
    if artifacts.is_dir():
        write_png(artifacts / "opencode-launcher-xxxhdpi-1.3.3.png", 192, legacy_preview)
        write_png(artifacts / "opencode-launcher-foreground-xxxhdpi-1.3.3.png", 432, fg)
        write_png(
            artifacts / "opencode-launcher-mask-preview-1.3.3.png",
            192,
            squircle_preview(192, adaptive_preview),
        )

    master = render(512, LEGACY_SCALE)
    cx, cy = 256, 288
    assert master[cy * 512 + cx][:3] == GRAY[:3], master[cy * 512 + cx]
    corner = master[8 * 512 + 8]
    assert corner[0] < 40 and corner[1] < 40 and corner[2] < 40, corner

    adaptive = render(512, ADAPTIVE_SCALE)
    inset = int(512 * 18 / 108)
    edge_px = adaptive[256 * 512 + inset]
    assert edge_px[0] < 50, f"adaptive mask edge is not dark: {edge_px}"
    # Inside the safe zone the official white chrome must still exist.
    white = sum(1 for r, g, b, a in adaptive if r > 240)
    assert white > 1000, white
    print("Wrote official-scale full-bleed launcher PNGs under", OUT)


if __name__ == "__main__":
    main()
