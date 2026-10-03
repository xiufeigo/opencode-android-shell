import sys
import xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
p = sys.argv[1]
filt = sys.argv[2] if len(sys.argv) > 2 else None
t = ET.parse(p)
for n in t.iter('node'):
    txt = (n.get('text','') or '').replace('\t', ' ')
    desc = (n.get('content-desc','') or '').replace('\t', ' ')
    b = n.get('bounds','')
    pw = n.get('password','')
    cls = n.get('class','').split('.')[-1]
    s = txt or desc
    if not s and cls != 'EditText':
        continue
    if filt and filt.lower() not in s.lower():
        continue
    print(f"{s}\t{b}\tpw={pw}\t{cls}\tfoc={n.get('focused','')}")
