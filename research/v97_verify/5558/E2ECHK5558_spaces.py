# E2ECHK5558_spaces.py - print the USER rows of a session's messages byte-exactly.
# The point of the v75 check is whitespace, so every user text is printed as a
# repr with a visible dot for every space, plus its len() and a hex tail.
# Usage: python E2ECHK5558_spaces.py <messages.json>
import json
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

path = sys.argv[1]
with open(path, encoding='utf-8') as f:
    data = json.load(f)

rows = data.get('data', data) if isinstance(data, dict) else data
print('total rows: %d' % len(rows))
n = 0
for m in rows:
    if m.get('type') != 'user' and m.get('role') != 'user':
        continue
    n += 1
    txt = m.get('text')
    if txt is None:
        for p in m.get('parts', []) or []:
            if p.get('type') == 'text':
                txt = p.get('text')
                break
    if txt is None:
        txt = (m.get('metadata') or {}).get('displayText', '')
    vis = txt.replace(' ', '\u00b7')
    print('--- user row #%d  id=%s' % (n, m.get('id')))
    print('    repr      : %r' % txt)
    print('    visible   : %s' % vis)
    print('    len()     : %d' % len(txt))
    print('    tail bytes: %s' % ' '.join('%02x' % b for b in txt[-6:].encode('utf-8')))
    if ' ' in txt:
        i = txt.index(' ')
        print('    first space at index %d, context %r' % (i, txt[max(0, i - 5):i + 6]))
    if txt.endswith(' '):
        print('    >>> TRAILING SPACE PRESENT (ends with U+0020)')
    else:
        print('    >>> no trailing space (ends with %r)' % txt[-1:])
