import re, sys, os

NODE = re.compile(r'<node\b([^>]*?)/?>')
ATTR = re.compile(r'([a-z-]+)="([^"]*)"')

def dump(f):
    if not os.path.exists(f):
        print("MISSING", f)
        return
    c = open(f, encoding="utf-8", errors="replace").read()
    print("=" * 14, os.path.basename(f), "=" * 14)
    for m in NODE.finditer(c):
        a = dict(ATTR.findall(m.group(1)))
        t = a.get("text", "")
        d = a.get("content-desc", "")
        cls = a.get("class", "")
        b = a.get("bounds", "")
        if t or d:
            print("%-9s %-34s %-26s %s" % (cls.split(".")[-1], repr(t)[:34], repr(d)[:26], b))
        if cls.endswith("EditText"):
            print("   >>> EDITTEXT text=%r focused=%s bounds=%s" % (t, a.get("focused"), b))

for f in sys.argv[1:]:
    dump(f)
