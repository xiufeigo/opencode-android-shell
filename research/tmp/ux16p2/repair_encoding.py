"""UX16-P2 · Repair assets/www/index.html encoding.

HEAD~1 (cd088b3) blob is byte-clean valid UTF-8. In HEAD (05e48cd) the 3rd byte
of 156 three-byte UTF-8 sequences was clobbered to literal '?' (0x3F). Decoding
the damaged file yields, for every damaged char, exactly U+FFFD followed by '?'.

So for each damaged line we build a regex where each '\\ufffd?' becomes a single
wildcard capture, and full-match it against every clean line. A unique match
recovers the original char with certainty (no guessing).
"""
import re, sys, io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

CLEAN = r"research\tmp\ux16p2\blob-cd088b3.html"
CURR = r"webapp\src\main\assets\www\index.html"

with open(CLEAN, "rb") as f:
    clean_lines = f.read().decode("utf-8").splitlines()
with open(CURR, "rb") as f:
    raw = f.read()
cur_lines = raw.decode("utf-8", errors="replace").splitlines()

BROKEN = "\ufffd?"
print(f"clean lines={len(clean_lines)} current lines={len(cur_lines)}")

broken_idx = [i for i, l in enumerate(cur_lines) if "\ufffd" in l]
print(f"broken lines={len(broken_idx)}")

out = list(cur_lines)
unresolved = []
matched = 0
for i in broken_idx:
    line = cur_lines[i]
    parts = line.split(BROKEN)
    n_wild = len(parts) - 1
    if n_wild == 0:
        unresolved.append((i, "no-wildcard", line))
        continue
    pat = re.compile("(?s)" + "(.)".join(re.escape(p) for p in parts))
    hits = []
    for cl in clean_lines:
        m = pat.fullmatch(cl)
        if m:
            hits.append((cl, m.groups()))
    if len(hits) == 1:
        cl, groups = hits[0]
        out[i] = cl
        matched += 1
    elif len(hits) == 0:
        unresolved.append((i, "no-match", line))
    else:
        unresolved.append((i, f"ambiguous({len(hits)})", line))

print(f"matched={matched} unresolved={len(unresolved)}")
for i, why, line in unresolved:
    print(f"UNRESOLVED line {i+1} [{why}]: {line!r}")

with open(r"research\tmp\ux16p2\index.repaired.html", "w", encoding="utf-8", newline="") as f:
    f.write("\n".join(out) + "\n")
print("wrote research/tmp/ux16p2/index.repaired.html")
