# E2ECHK5556_geom.py - print ALL nodes (including unlabelled containers) with bounds,
# plus a "widest/window-sized container" report so the dialog CARD can be located
# exactly instead of guessed from a screenshot.
#
# Usage: python E2ECHK5556_geom.py <xml> [min_width]
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

path = sys.argv[1]
min_w = int(sys.argv[2]) if len(sys.argv) > 2 else 300

tree = ET.parse(path)
root = tree.getroot()

rows = []


def parse_bounds(b):
    if not b:
        return None
    try:
        # "[x1,y1][x2,y2]"
        x1, y1, x2, y2 = b[1:-1].replace('][', ',').replace('[', '').split(',')
        return int(x1), int(y1), int(x2), int(y2)
    except Exception:
        return None


def walk(node, depth=0):
    b = node.get('bounds', '')
    bb = parse_bounds(b)
    t = node.get('text', '')
    d = node.get('content-desc', '')
    cls = node.get('class', '')
    if bb:
        x1, y1, x2, y2 = bb
        w, h = x2 - x1, y2 - y1
        rows.append((depth, cls, t or d, x1, y1, x2, y2, w, h, b))
    for c in node:
        walk(c, depth + 1)


walk(root)

print("=== CONTAINERS with width >= %d px (candidate card / panel / scrim) ===" % min_w)
print("depth\tclass\tw\th\ttop\tbottom\ttxt\tbounds")
for (depth, cls, txt, x1, y1, x2, y2, w, h, b) in rows:
    if w >= min_w:
        print(f"{depth}\t{cls}\t{w}\t{h}\t{y1}\t{y2}\t{txt[:40]}\t{b}")

print()
print("=== ALL NODES (depth, class, bounds, text) ===")
for (depth, cls, txt, x1, y1, x2, y2, w, h, b) in rows:
    print(f"{depth}\t{cls}\t{b}\t{txt[:60]}")
