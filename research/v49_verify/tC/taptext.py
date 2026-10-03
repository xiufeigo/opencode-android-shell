#!/usr/bin/env python3
"""taptext.py — tap a node located by its text/content-desc substring.

Coordinate chasing by eye kept landing on the session-title field instead of
the message composer, so address nodes by content and let uiautomator supply
the bounds.

Usage: python3 taptext.py <substring> [--serial S] [--contains]
"""
import re
import subprocess
import sys

ADB = "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
serial = "emulator-5558"
args = sys.argv[1:]
if "--serial" in args:
    i = args.index("--serial")
    serial = args[i + 1]
    del args[i:i + 2]
contains = "--contains" in args
if contains:
    args.remove("--contains")
needle = args[0]

subprocess.run([ADB, "-s", serial, "shell", "rm", "-f", "/sdcard/t.xml"], capture_output=True)
d = subprocess.run([ADB, "-s", serial, "shell", "uiautomator", "dump", "/sdcard/t.xml"],
                   capture_output=True, text=True)
if "dumped to" not in (d.stdout + d.stderr):
    sys.exit("dump failed: " + d.stdout + d.stderr)
subprocess.run([ADB, "-s", serial, "pull", "/sdcard/t.xml", "C:/Windows/Temp/t.xml"],
               capture_output=True)
x = open("C:/Windows/Temp/t.xml", encoding="utf-8").read()

hits = []
for m in re.finditer(r"<node[^>]*>", x):
    s = m.group(0)
    t = re.search(r'text="([^"]*)"', s)
    dsc = re.search(r'content-desc="([^"]*)"', s)
    vals = [v.group(1) for v in (t, dsc) if v and v.group(1)]
    if not any((v == needle) if not contains else (needle in v) for v in vals):
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    hits.append((x1, y1, x2, y2, vals))

if not hits:
    sys.exit("NOT FOUND: " + needle)
for x1, y1, x2, y2, vals in hits:
    print(f"{needle!r} -> ({x1},{y1})-({x2},{y2}) {vals}")
x1, y1, x2, y2, vals = hits[-1]          # last match = the one on top
cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
subprocess.run([ADB, "-s", serial, "shell", "input", "tap", str(cx), str(cy)], capture_output=True)
print("tapped", cx, cy)
