import json, sys, time, urllib.request, base64, subprocess, os, re

A = "Basic " + base64.b64encode(b"opencode:opencode").decode()
S = "emulator-5558"
ADB = "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"


def get(p):
    r = urllib.request.Request("http://127.0.0.1:4095/api" + p,
                               headers={"Authorization": A})
    raw = urllib.request.urlopen(r, timeout=20).read().decode("utf-8")
    try:
        d = json.loads(raw)
    except json.JSONDecodeError:
        # The backend intermittently emits a raw control character in tool
        # output, which is invalid JSON. strict=False tolerates it.
        d = json.loads(raw, strict=False)
    return d["data"] if isinstance(d, dict) and "data" in d else d


def banner_state():
    """y of the 首页 button: 127 = no banner, 469 = banner pushing the layout."""
    subprocess.run([ADB, "-s", S, "shell", "uiautomator", "dump", "/sdcard/c.xml"],
                   capture_output=True)
    subprocess.run([ADB, "-s", S, "pull", "/sdcard/c.xml", "/tmp/c.xml"],
                   capture_output=True)
    try:
        x = open("/tmp/c.xml", encoding="utf-8").read()
    except Exception:
        return "?"
    m = re.search(r'content-desc="首页"[^>]*bounds="\[(\d+),(\d+)\]', x)
    y = m.group(2) if m else "NONE"
    sid = re.search(r"(ses_[A-Za-z0-9]+)", x)
    return y, (sid.group(1) if sid else "")


SECONDS = int(sys.argv[1]) if len(sys.argv) > 1 else 180
end = time.time() + SECONDS
last_sess = {}
events = []
while time.time() < end:
    now = time.strftime("%H:%M:%S")
    y, sid = banner_state()
    # Who on the shared backend moved in the last ~40s?
    hot = []
    for s in get("/session")[:15]:
        upd = s.get("time", {}).get("updated", 0)
        if upd and (time.time() * 1000 - upd) < 45000:
            hot.append((s["id"], s.get("title"), time.strftime("%H:%M:%S", time.localtime(upd / 1000))))
    if y != "127":
        events.append((now, "BANNER", sid, hot))
        print(now, "BANNER sid=", sid, "| hot:", hot, flush=True)
    else:
        last_sess[now] = hot
    time.sleep(2)

print("\n--- banner events ---")
for e in events:
    print(e)
