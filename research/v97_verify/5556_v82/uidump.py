# -*- coding: utf-8 -*-
"""Print clickable/text nodes from a uiautomator XML dump. Usage: python uidump.py <file> [text-filter]"""
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
flt = sys.argv[2] if len(sys.argv) > 2 else None
tree = ET.parse(path)
for n in tree.iter("node"):
    text = n.get("text") or ""
    desc = n.get("content-desc") or ""
    cls = n.get("class") or ""
    b = n.get("bounds") or ""
    if flt and (flt not in text and flt not in desc and flt not in cls):
        continue
    if text or desc or n.get("clickable") == "true":
        print(f"{cls} text={text!r} desc={desc!r} bounds={b} clickable={n.get('clickable')} scrollable={n.get('scrollable')}")
