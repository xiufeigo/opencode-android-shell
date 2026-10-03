import re, subprocess, sys, time, os

ADB = r"C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
S = "emulator-5556"


def sh(*args):
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    return subprocess.run([ADB, "-s", S, *args], capture_output=True, env=env).stdout.decode("utf-8", "replace")


def dump():
    out = sh("exec-out", "uiautomator", "dump", "/dev/tty")
    out = out[out.index("<hierarchy"):]
    out = out[:out.rfind("</hierarchy>") + 8]
    nodes = []
    for m in re.finditer(r"<node[^>]*>", out):
        t = m.group(0)
        g = lambda k: re.search(k + r'="([^"]*)"', t).group(1)
        b = [int(v) for v in re.findall(r"\d+", g("bounds"))]
        nodes.append({"text": g("text"), "desc": g("content-desc"), "click": g("clickable"), "b": b})
    return nodes


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def tap_node(n):
    tap((n["b"][0] + n["b"][2]) // 2, (n["b"][1] + n["b"][3]) // 2)


def find(nodes, text=None, desc=None):
    for n in nodes:
        if text is not None and n["text"] == text:
            return n
        if desc is not None and n["desc"] == desc:
            return n
    return None


def chip(nodes):
    n = find(nodes, desc="Select where to run session")
    if not n:
        return "?"
    return "".join(x["text"] for x in nodes if x["text"] and n["b"][0] <= x["b"][0] and x["b"][2] <= n["b"][2])


def open_menu():
    """open the workspace dropdown; returns the popup's node list (or None)"""
    for _ in range(4):
        n = find(dump(), desc="Select where to run session")
        if n:
            tap_node(n)
        time.sleep(1.2)
        nodes = dump()
        if find(nodes, "Worktrees"):
            return nodes
    return None


def worktree_row(nodes):
    ref = find(nodes, "Refresh worktrees")
    if not ref:
        return None
    for n in nodes:
        if n["click"] == "true" and n["b"][3] <= ref["b"][1] and n["b"][1] < ref["b"][1] and 150 < n["b"][2] - n["b"][0] < 260:
            return n["b"]
    return None


def reset_local():
    """select the Local repository row so the chip (and the popup anchor) is stable"""
    for _ in range(4):
        nodes = open_menu()
        if nodes is None:
            return False
        local = find(nodes, "Local repository")
        if local is None:
            return False
        tap_node(local)
        time.sleep(1.3)
        after = dump()
        if chip(after) == "?" or "lucky" not in chip(after):
            return True
    return False


def classify(nodes, before):
    if find(nodes, "Delete worktree?"):
        return "DIALOG"
    if find(nodes, "Create session"):
        c = chip(nodes)
        return "ROW-SELECTED" if "lucky" in c else "DISMISSED(no select)"
    return "STILL-OPEN"


points = [(int(a), int(b)) for a, b in (p.split(",") for p in sys.argv[1:])]
for x, y in points:
    if not reset_local():
        print("tap %4d,%4d -> RESET-FAILED" % (x, y), flush=True)
        continue
    pre = open_menu()
    if pre is None:
        print("tap %4d,%4d -> NOOPEN" % (x, y), flush=True)
        continue
    geom = "row=%s x=%s chip=%s" % (worktree_row(pre), find(pre, desc="Delete worktree")["b"], chip(dump()))
    tap(x, y)
    time.sleep(1.5)
    st = classify(dump(), pre)
    print("tap %4d,%4d -> %-20s %s" % (x, y, st, geom), flush=True)
    if st == "DIALOG":
        for _ in range(3):
            d = find(dump(), "Cancel")
            if d:
                tap_node(d)
                time.sleep(1.0)
                break
