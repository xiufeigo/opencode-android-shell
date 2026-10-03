import subprocess, sys, re, xml.etree.ElementTree as ET
ADB = r"C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
S = "emulator-5560"
filt = sys.argv[1] if len(sys.argv) > 1 else ""
subprocess.run([ADB, "-s", S, "shell", "uiautomator", "dump", "/sdcard/uix.xml"],
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
raw = subprocess.run([ADB, "-s", S, "exec-out", "cat", "/sdcard/uix.xml"],
                     capture_output=True).stdout
try:
    root = ET.fromstring(raw)
except Exception as e:
    m = re.search(rb'char="(\d+)"', str(e).encode())
    if m:
        i = int(m.group(1))
        raw = raw[:i] + raw[i+1:]
        root = ET.fromstring(raw)
    else:
        raise
for n in root.iter("node"):
    t = n.get("text", "")
    d = n.get("content-desc", "")
    if not t and not d:
        continue
    s = t or d
    if filt and filt not in s:
        continue
    print("%-46s %-28s %s" % (s.replace("\n", "\\n")[:46], n.get("class", "")[-27:], n.get("bounds")))
