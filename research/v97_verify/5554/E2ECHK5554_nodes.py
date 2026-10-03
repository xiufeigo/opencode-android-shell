# E2ECHK5554_nodes.py - print a compact node table from a uiautomator dump.
# One line per node: <text-or-desc>\t<bounds>\t<pw>\t<focus>\t<class>\t<clickable>
# Adapted from research/v95_verify/5560/E2ECHK5560_nodes.py (device-generic).
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

path = sys.argv[1]
tree = ET.parse(path)
root = tree.getroot()


def walk(node):
    t = node.get('text', '')
    d = node.get('content-desc', '')
    label = t if t else d
    b = node.get('bounds', '')
    pw = node.get('password', '')
    foc = node.get('focused', '')
    cls = node.get('class', '')
    clk = node.get('clickable', '')
    if label or cls.endswith('EditText'):
        print(f"{label}\t{b}\tpw={pw}\tfoc={foc}\t{cls}\tclk={clk}")
    for c in node:
        walk(c)


walk(root)
