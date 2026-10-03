import sys
import xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
t = ET.parse(sys.argv[1])
for n in t.iter('node'):
    if n.get('clickable') != 'true':
        continue
    txt = (n.get('text','') or n.get('content-desc',''))
    print(f"{txt or '(no label)'}\t{n.get('bounds')}\t{n.get('class','').split('.')[-1]}")
