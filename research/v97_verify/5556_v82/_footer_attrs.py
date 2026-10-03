import sys, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
t = ET.parse(sys.argv[1]); r = t.getroot()
def walk(n):
    txt = n.get("text") or n.get("content-desc")
    if txt in ("Add server", "Cancel", "Password", "Host or SSH command"):
        print("  text=%-20r class=%-24s bounds=%-22s clickable=%s enabled=%s focused=%s scrollable=%s password=%s"
              % (txt, n.get("class"), n.get("bounds"), n.get("clickable"),
                 n.get("enabled"), n.get("focused"), n.get("scrollable"), n.get("password")))
    for c in n: walk(c)
walk(r)
