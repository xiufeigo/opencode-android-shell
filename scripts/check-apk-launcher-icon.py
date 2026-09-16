#!/usr/bin/env python3
"""Extract launcher PNGs from a built APK and assert full-bleed dark corners."""
from __future__ import annotations

import io
import struct
import sys
import zipfile
import zlib
from pathlib import Path

DARK_MAX = 40


def read_png(data: bytes) -> tuple[int, int, bytes]:
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    offset = 8
    width = height = 0
    idat = bytearray()
    while offset + 12 <= len(data):
        (length,) = struct.unpack(">I", data[offset : offset + 4])
        tag = data[offset + 4 : offset + 8]
        chunk = data[offset + 8 : offset + 8 + length]
        if tag == b"IHDR":
            width, height, bit, color = struct.unpack(">IIBB", chunk[:10])
            assert bit == 8 and color == 6, (bit, color)
        elif tag == b"IDAT":
            idat.extend(chunk)
        elif tag == b"IEND":
            break
        offset += 12 + length
    raw = zlib.decompress(bytes(idat))
    rgba = bytearray()
    stride = width * 4 + 1
    assert len(raw) == stride * height
    for y in range(height):
        row = raw[y * stride : (y + 1) * stride]
        assert row[0] == 0
        rgba.extend(row[1:])
    return width, height, bytes(rgba)


def px(rgba: bytes, w: int, x: int, y: int) -> tuple[int, int, int, int]:
    i = (y * w + x) * 4
    return rgba[i], rgba[i + 1], rgba[i + 2], rgba[i + 3]


def squircle_preview(w: int, h: int, rgba: bytes, wallpaper=(255, 255, 255), pad=48) -> bytes:
    canvas = w + pad * 2
    out = bytearray()
    r = w / 2.0 * 0.98
    cx = cy = pad + (w - 1) / 2.0
    p = 4.0
    for y in range(canvas):
        out.append(0)
        for x in range(canvas):
            nx = abs(x - cx) / r
            ny = abs(y - cy) / r
            if 0 <= x - pad < w and 0 <= y - pad < h and nx**p + ny**p <= 1.0:
                pr, pg, pb, pa = px(rgba, w, x - pad, y - pad)
                out.extend((pr, pg, pb, pa))
            else:
                out.extend((*wallpaper, 255))
    compressed = zlib.compress(bytes(out), 9)

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", canvas, canvas, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", compressed) + chunk(b"IEND", b"")


def main() -> int:
    apk = Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk")
    out_dir = Path(sys.argv[2] if len(sys.argv) > 2 else "/opt/cursor/artifacts")
    z = zipfile.ZipFile(apk)
    names = [n for n in z.namelist() if "ic_launcher" in n]
    print("apk launcher entries:")
    for n in sorted(names):
        print(" ", n)
    adaptive = [n for n in names if n.endswith(".xml")]
    if adaptive:
        raise SystemExit(f"APK still contains adaptive XML: {adaptive}")
    png_names = [n for n in names if n.endswith("ic_launcher.png")]
    if not png_names:
        raise SystemExit("no ic_launcher.png in APK")
    # Prefer xxxhdpi
    png_names.sort(key=lambda n: ("xxxhdpi" not in n, -len(n)))
    chosen = png_names[0]
    data = z.read(chosen)
    w, h, rgba = read_png(data)
    print(f"extracted {chosen} {w}x{h}")
    corners = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
    for x, y in corners:
        r, g, b, a = px(rgba, w, x, y)
        print(f"  corner {x},{y} rgba=({r},{g},{b},{a})")
        if a < 250 or r >= DARK_MAX or g >= DARK_MAX or b >= DARK_MAX:
            raise SystemExit(f"corner {x},{y} is not dark opaque: {(r, g, b, a)}")
    out_dir.mkdir(parents=True, exist_ok=True)
    extracted = out_dir / "apk-ic_launcher-xxxhdpi-1.3.7.png"
    extracted.write_bytes(data)
    preview = out_dir / "apk-ic_launcher-squircle-white-1.3.7.png"
    preview.write_bytes(squircle_preview(w, h, rgba, wallpaper=(255, 255, 255)))
    sky = out_dir / "apk-ic_launcher-squircle-wallpaper-1.3.7.png"
    sky.write_bytes(squircle_preview(w, h, rgba, wallpaper=(0x9E, 0xD8, 0xF7)))
    print("wrote", extracted)
    print("wrote", preview)
    print("ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
