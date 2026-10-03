import re, subprocess, time

ADB = r"C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5554"
RC = "\u6700\u8fd1\u5173\u95ed"  # 最近关闭


def dump():
    subprocess.run([ADB, "-s", SERIAL, "shell", "uiautomator", "dump", "/sdcard/pq.xml"], capture_output=True)
    subprocess.run([ADB, "-s", SERIAL, "pull", "/sdcard/pq.xml", "pq.xml"], capture_output=True)
    x = open("pq.xml", encoding="utf-8").read()
    projects, closes, rc_y = [], [], None
    for m in re.finditer(r"<node[^>]*?/?>", x):
        s = m.group(0)
        t = re.search(r'text="([^"]*)"', s)
        cd = re.search(r'content-desc="([^"]*)"', s)
        b = re.search(r'bounds="([^"]*)"', s)
        if not b:
            continue
        y = int(re.match(r"\[(\d+),(\d+)\]", b.group(1)).group(2))
        if t and t.group(1) == RC and rc_y is None:
            rc_y = y
        if t and re.match(r"^[A-Za-z][A-Za-z0-9_.-]*$", t.group(1)) and len(t.group(1)) > 3 and 300 < y < 800:
            projects.append(t.group(1))
        if cd and cd.group(1) in ("Close", "\u5173\u95ed") and y < 800:
            closes.append((y, b.group(1)))
    return projects, closes, rc_y


def center(b):
    mm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return (int(mm.group(1)) + int(mm.group(3))) // 2, (int(mm.group(2)) + int(mm.group(4))) // 2


projects, closes, rc_y = dump()
print("BEFORE (%d): %s" % (len(projects), projects), flush=True)

step = 0
while True:
    # only consider Close buttons that sit above the "最近关闭" header,
    # i.e. that belong to the live project list
    live = [(y, b) for (y, b) in closes if rc_y is None or y < rc_y]
    if not live:
        break
    y, b = min(live)
    before = list(projects)
    cx, cy = center(b)
    subprocess.run([ADB, "-s", SERIAL, "shell", "input", "tap", str(cx), str(cy)], capture_output=True)
    time.sleep(2)
    projects, closes, rc_y = dump()
    removed = [n for n in before if n not in projects]
    print("step %d: Close@%s -> %d left %s | removed=%s" % (step + 1, b, len(projects), projects, removed), flush=True)
    if not removed:
        print("!! no row removed - stopping to avoid tapping the recently-closed list", flush=True)
        break
    step += 1
    if step > 15:
        break

print("FINAL projects: %s" % projects, flush=True)
print("recently-closed header present: %s" % (rc_y is not None), flush=True)
