# formrect.py - print the form-body ScrollView rect + key field bounds from a dump.
import sys, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
root = ET.parse(sys.argv[1]).getroot()
parent = {}
for p in root.iter():
    for c in p:
        parent[c] = p

scrolls, anchors, fields, labels = [], [], [], []
for n in root.iter():
    cls = n.get('class', '')
    b = n.get('bounds', '')
    t = n.get('text', '') or n.get('content-desc', '')
    if cls == 'android.widget.ScrollView':
        scrolls.append(b)
    if cls == 'android.widget.EditText':
        fields.append((b, n.get('password', ''), t))
    if t:
        labels.append((t, b))
    if t in ('Host or SSH command', 'Server name (optional)', 'Authentication', 'Passphrase') or t.startswith('Password'):
        anchors.append(b)
    if cls == 'android.widget.EditText' and b.startswith('[138,'):
        anchors.append(b)


def bb(s):
    s = s.replace('[', '').replace(']', ',').replace(' ', '')
    a = s.split(',')
    return [int(a[0]), int(a[1]), int(a[2]), int(a[3])]


best, area = None, 10 ** 12
for s in scrolls:
    sb = bb(s)
    for a in anchors:
        ab = bb(a)
        if ab[0] >= sb[0] and ab[1] >= sb[1] and ab[2] <= sb[2] and ab[3] <= sb[3]:
            ar = (sb[2] - sb[0]) * (sb[3] - sb[1])
            if 0 < ar < area:
                best, area = sb, ar
            break
print(f"FORM_RECT {best[0]},{best[1]},{best[2]},{best[3]}" if best else "FORM_RECT NONE")
for (b, pw, t) in fields:
    if b.startswith('[138,'):
        print(f"FIELD pw={pw} text={t!r} {b}")
for (t, b) in labels:
    if t in ('Host or SSH command', 'Server name (optional)', 'Authentication', 'Add server', 'Cancel'):
        print(f"LABEL {t!r} {b}")
