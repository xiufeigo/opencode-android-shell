#!/usr/bin/env python3
"""Lightweight adb UI controller: dump, find by text/desc, tap, swipe, screenshot."""
import subprocess, re, sys, os, json, time, argparse, xml.etree.ElementTree as ET

ADB = r"C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
TMPDIR = os.environ.get("TEMP", r"C:/Users/xiufe/AppData/Local/Temp")
UI_XML = os.path.join(TMPDIR, "e2e_v39.xml")
SHOT_DIR = r"C:/sensorsdata/main/program/opencode-android-shell/research/v39_verify"
os.makedirs(SHOT_DIR, exist_ok=True)

def sh(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    return r.returncode, r.stdout, r.stderr

def adb(*args, **kw):
    return sh([ADB] + list(args), **kw)

def dump_ui(serial):
    if os.path.exists(UI_XML): os.remove(UI_XML)
    adb("-s", serial, "shell", "uiautomator", "dump", "/sdcard/v39.xml")
    adb("-s", serial, "pull", "/sdcard/v39.xml", UI_XML)
    if not os.path.exists(UI_XML):
        raise RuntimeError("dump pull failed")
    with open(UI_XML, "rb") as f:
        raw = f.read()
    return raw.decode("utf-8", errors="replace")

def parse_bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
    if not m: return None
    return tuple(int(x) for x in m.groups())

def nodes(xml):
    root = ET.fromstring(xml)
    return list(root.iter())

def find_text(xml, text, contains=False):
    out = []
    for n in nodes(xml):
        t = n.get("text") or ""
        if (contains and text in t) or (not contains and t == text):
            out.append(n)
    return out

def find_desc(xml, text, contains=False):
    out = []
    for n in nodes(xml):
        t = n.get("content-desc") or ""
        if (contains and text in t) or (not contains and t == text):
            out.append(n)
    return out

def center(n):
    b = parse_bounds(n.get("bounds"))
    if not b: return None
    return ((b[0]+b[2])//2, (b[1]+b[3])//2)

def tap_n(n):
    c = center(n)
    if not c:
        print("BAD BOUNDS", n.get("bounds")); sys.exit(3)
    adb("-s", serial, "shell", "input", "tap", str(c[0]), str(c[1]))
    print(f"tapped {c[0]},{c[1]} ({n.get('text') or n.get('content-desc')})")

def tap_text(text, contains=False, serial=None):
    xml = dump_ui(serial)
    h = find_text(xml, text, contains)
    if not h:
        print(f"NOT FOUND text={text!r}"); sys.exit(2)
    tap_n(h[0])

def tap_desc(text, contains=False, serial=None):
    xml = dump_ui(serial)
    h = find_desc(xml, text, contains)
    if not h:
        print(f"NOT FOUND desc={text!r}"); sys.exit(2)
    tap_n(h[0])

def tap_xy(x, y, serial):
    adb("-s", serial, "shell", "input", "tap", str(x), str(y))
    print(f"tapped {x},{y}")

def swipe(fx, fy, tx, ty, dur, serial):
    adb("-s", serial, "shell", "input", "swipe", str(fx), str(fy), str(tx), str(ty), str(dur))
    print(f"swiped ({fx},{fy}) -> ({tx},{ty}) over {dur}ms")

def press_xy(fx, fy, hold_ms, serial):
    adb("-s", serial, "shell", "input", "swipe", str(fx), str(fy), str(fx), str(fy), str(hold_ms))
    print(f"held ({fx},{fy}) for {hold_ms}ms")

def screenshot(name, serial):
    path = os.path.join(SHOT_DIR, f"{name}.png")
    adb("-s", serial, "shell", "screencap", "-p", f"/sdcard/{name}.png")
    adb("-s", serial, "pull", f"/sdcard/{name}.png", path)
    sz = os.path.getsize(path)
    print(f"saved {path} ({sz} bytes)")

def texts_only(serial):
    xml = dump_ui(serial)
    for n in nodes(xml):
        t = n.get("text") or ""
        d = n.get("content-desc") or ""
        b = n.get("bounds") or ""
        if t: print(f"T:{t}  {b}")
        if d: print(f"D:{d}  {b}")

def summary(serial):
    xml = dump_ui(serial)
    items = []
    for n in nodes(xml):
        t = n.get("text") or ""
        d = n.get("content-desc") or ""
        b = n.get("bounds") or ""
        if t: items.append(f"T:{t}@{b}")
        if d: items.append(f"D:{d}@{b}")
    print(" | ".join(items))

def type_chars(text, serial, clear_first=True):
    if clear_first:
        adb("-s", serial, "shell", "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(120):
            adb("-s", serial, "shell", "input", "keyevent", "KEYCODE_DEL")
    for ch in text:
        if ch.islower(): adb("-s", serial, "shell", "input", "keyevent", f"KEYCODE_{ch.upper()}")
        elif ch.isupper(): adb("-s", serial, "shell", "input", "keycombination", "KEYCODE_SHIFT_LEFT", f"KEYCODE_{ch}")
        elif ch.isdigit(): adb("-s", serial, "shell", "input", "keyevent", f"KEYCODE_{ch}")
        elif ch == ".": adb("-s", serial, "shell", "input", "keyevent", "KEYCODE_PERIOD")
        elif ch == "-": adb("-s", serial, "shell", "input", "keyevent", "KEYCODE_MINUS")
        elif ch == "_": adb("-s", serial, "shell", "input", "keycombination", "KEYCODE_SHIFT_LEFT", "KEYCODE_MINUS")
        elif ch == " ": adb("-s", serial, "shell", "input", "keyevent", "KEYCODE_SPACE")
        elif ch == "(": adb("-s", serial, "shell", "input", "keycombination", "KEYCODE_SHIFT_LEFT", "KEYCODE_9")
        elif ch == ")": adb("-s", serial, "shell", "input", "keycombination", "KEYCODE_SHIFT_LEFT", "KEYCODE_0")
        elif ch == "/": adb("-s", serial, "shell", "input", "keyevent", "KEYCODE_SLASH")
        elif ch == ":": adb("-s", serial, "shell", "input", "keycombination", "KEYCODE_SHIFT_LEFT", "KEYCODE_SEMICOLON")
        elif ch == "@": adb("-s", serial, "shell", "input", "keyevent", "KEYCODE_AT")
        else:
            print(f"NO MAPPING for char {ch!r}")
    print(f"typed {text!r}")

def keyevent(code, serial):
    adb("-s", serial, "shell", "input", "keyevent", code)
    print(f"keyevent {code}")

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5558")
parser.add_argument("cmd")
parser.add_argument("--text", default="")
parser.add_argument("--desc", default="")
parser.add_argument("--contains", action="store_true")
parser.add_argument("--xy", default="")
parser.add_argument("--from", dest="frm", default="")
parser.add_argument("--to", default="")
parser.add_argument("--dur", type=int, default=400)
parser.add_argument("--name", default="")
parser.add_argument("--keycode", default="")
parser.add_argument("--chars", default="")
args = parser.parse_args()
serial = args.serial

if args.cmd == "dump": summary(serial)
elif args.cmd == "texts": texts_only(serial)
elif args.cmd == "tap-text":
    xml = dump_ui(serial); h = find_text(xml, args.text, args.contains)
    if not h: print(f"NOT FOUND text={args.text!r}"); sys.exit(2)
    tap_n(h[0])
elif args.cmd == "tap-desc":
    xml = dump_ui(serial); h = find_desc(xml, args.desc, args.contains)
    if not h: print(f"NOT FOUND desc={args.desc!r}"); sys.exit(2)
    tap_n(h[0])
elif args.cmd == "tap-xy":
    x,y = args.xy.split(","); tap_xy(int(x), int(y), serial)
elif args.cmd == "swipe":
    fx,fy = args.frm.split(","); tx,ty = args.to.split(",")
    swipe(int(fx),int(fy),int(tx),int(ty),args.dur,serial)
elif args.cmd == "press":
    x,y = args.xy.split(","); press_xy(int(x),int(y),args.dur,serial)
elif args.cmd == "shot": screenshot(args.name or "shot", serial)
elif args.cmd == "key": keyevent(args.keycode, serial)
elif args.cmd == "type": type_chars(args.chars, serial)
else: print("unknown cmd:", args.cmd); sys.exit(1)
