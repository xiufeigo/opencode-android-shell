# anc.py - print node table WITH enabled, plus ancestor chain for a target label.
# Usage: python anc.py <dump.xml> [target-label-substring]
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
path = sys.argv[1]
target = sys.argv[2] if len(sys.argv) > 2 else None
tree = ET.parse(path)
root = tree.getroot()
parent = {}
for p in root.iter():
    for c in p:
        parent[c] = p


def lab(n):
    t = n.get('text', '') or n.get('content-desc', '')
    return t


def attrs(n):
    b = n.get('bounds', '')
    return (f"{b}\ten={n.get('enabled')}\tclk={n.get('clickable')}"
            f"\tfoc={n.get('focused')}\tpw={n.get('password')}\t{n.get('class', '')}")


def walk(n, depth=0):
    t = lab(n)
    cls = n.get('class', '')
    if t or cls.endswith('EditText'):
        print(f"{t}\t{attrs(n)}")
    for c in n:
        walk(c, depth + 1)


print('=== NODE TABLE (with enabled) ===')
walk(root)

if target:
    print(f'\n=== ANCESTOR CHAIN for label containing {target!r} ===')
    found = 0
    for n in root.iter():
        if target in lab(n):
            found += 1
            print(f'-- match {found}: {lab(n)!r}')
            cur = n
            d = 0
            while cur is not None and d < 8:
                print(f'   anc[{d}] {attrs(cur)}')
                cur = parent.get(cur)
                d += 1
    if not found:
        print('  NO NODE WITH THAT LABEL IN THIS DUMP')
