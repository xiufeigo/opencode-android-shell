#!/usr/bin/env python3
"""Run a JS file (or inline expr) through CDP and print the JSON result."""
import sys, json, io, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cdp

path = sys.argv[1]
raw = io.open(path, encoding='utf-8-sig').read()
out = cdp.raw(raw, timeout=int(sys.argv[2]) if len(sys.argv) > 2 else 30)
if isinstance(out, str):
    try:
        out = json.loads(out)
    except Exception:
        pass
print(json.dumps(out, ensure_ascii=False, indent=1))