import re, sys, os

d = os.path.dirname(os.path.abspath(__file__))
for f in ['u3.xml', 'u4.xml', 'u5.xml', 'u6.xml', 'ui.xml', 'u2.xml']:
    p = os.path.join(d, f)
    if not os.path.isfile(p):
        continue
    s = open(p, encoding='utf-8', errors='replace').read()
    print('=====', f, 'len', len(s))
    for m in re.finditer(r'<node[^>]*>', s):
        t = m.group(0)

        def g(k):
            mm = re.search(k + r'="([^"]*)"', t)
            return mm.group(1) if mm else ''
        c = g('class')
        txt = g('text')
        desc = g('content-desc')
        b = g('bounds')
        rc = g('resource-id')
        if c.endswith('EditText') or txt or desc:
            print('%-38s text=%-28r desc=%-24r rid=%-30s %s' % (c, txt, desc, rc, b))
