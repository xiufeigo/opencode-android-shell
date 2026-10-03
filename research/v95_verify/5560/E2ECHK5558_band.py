import sys
import xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
t = ET.parse(sys.argv[1])
y0 = int(sys.argv[2]); y1 = int(sys.argv[3])
x0 = int(sys.argv[4]) if len(sys.argv) > 4 else 0
for n in t.iter('node'):
    b = n.get('bounds', '')
    if not b.startswith('['):
        continue
    import re
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
    if not m:
        continue
    xa, ya, xb, yb = map(int, m.groups())
    if ya >= y0 and yb <= y1 and xa >= x0:
        txt = (n.get('text','') or n.get('content-desc',''))
        print(f"{b}\t{txt or '(no label)'}\t{n.get('class','').split('.')[-1]}\tclick={n.get('clickable')}\tlong={n.get('long-clickable')}")
