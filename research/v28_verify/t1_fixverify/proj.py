import re, sys, subprocess, time

ADB = r"C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5554"


def dump():
    subprocess.run([ADB, "-s", SERIAL, "shell", "uiautomator", "dump", "/sdcard/pp.xml"], capture_output=True)
    subprocess.run([ADB, "-s", SERIAL, "pull", "/sdcard/pp.xml", "pp.xml"], capture_output=True)
    x = open("pp.xml", encoding="utf-8").read()
    names, closes = [], []
    for m in re.finditer(r"<node[^>]*?/?>", x):
        s = m.group(0)
        t = re.search(r'text="([^"]*)"', s)
        cd = re.search(r'content-desc="([^"]*)"', s)
        b = re.search(r'bounds="([^"]*)"', s)
        if not b:
            continue
        y = int(re.match(r"\[(\d+),(\d+)\]", b.group(1)).group(2))
        if t and 300 < y < 780 and re.match(r"^[A-Za-z][A-Za-z0-9_.-]*$", t.group(1)) and len(t.group(1)) > 3:
            names.append(t.group(1))
        if cd and cd.group(1) in ("Close", "\u5173\u95ed") and y < 800:
            closes.append(b.group(1))
    return names, closes


def center(b):
    mm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return (int(mm.group(1)) + int(mm.group(3))) // 2, (int(mm.group(2)) + int(mm.group(4))) // 2


def alltext():
    subprocess.run([ADB, "-s", SERIAL, "shell", "uiautomator", "dump", "/sdcard/pt.xml"], capture_output=True)
    subprocess.run([ADB, "-s", SERIAL, "pull", "/sdcard/pt.xml", "pt.xml"], capture_output=True)
    x = open("pt.xml", encoding="utf-8").read()
    out = []
    for m in re.finditer(r"<node[^>]*?/?>", x):
        t = re.search(r'text="([^"]*)"', m.group(0))
        if t and t.group(1):
            out.append("".join(c if ord(c) < 128 else "U+%04X" % ord(c) for c in t.group(1)))
    return out


names, closes = dump()
print("BEFORE (%d): %s" % (len(names), names), flush=True)

step = 0
while closes:
    step += 1
    target = closes[0]
    cx, cy = center(target)
    before = [n for n in names]
    subprocess.run([ADB, "-s", SERIAL, "shell", "input", "tap", str(cx), str(cy)], capture_output=True)
    time.sleep(2)
    names, closes = dump()
    removed = [n for n in before if n not in names]
    print("step %d: tapped Close@%s -> remaining %d %s | removed=%s" % (step, target, len(names), names, removed), flush=True)
    if step > 12:
        break

print("EMPTY-STATE TEXT:", alltext(), flush=True)
