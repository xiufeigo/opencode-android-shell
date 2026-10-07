import re, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

CLEAN = r"research\tmp\ux16p2\blob-cd088b3.html"
CURR = r"webapp\src\main\assets\www\index.html"

cur_raw = open(CURR, "rb").read()
clean_lines = open(CLEAN, "rb").read().decode("utf-8").splitlines()
cur_lines = cur_raw.decode("utf-8", errors="replace").splitlines()

# --- 1. byte-level pattern analysis -------------------------------------
# find every 'invalid seq' (2 lead bytes) immediately followed by literal '?'
sites = []
i = 0
n = len(cur_raw)
while i < n:
    c = cur_raw[i]
    if c < 0x80:
        i += 1; continue
    L = 2 if 0xC2 <= c <= 0xDF else 3 if 0xE0 <= c <= 0xEF else 4 if 0xF0 <= c <= 0xF4 else 0
    if L == 0:
        i += 1; continue
    ok = i + L <= n and all(0x80 <= cur_raw[i + k] <= 0xBF for k in range(1, L))
    if ok:
        i += L
    else:
        sites.append(i)
        i += L

print(f"invalid sites={len(sites)}")
after = {}
prefixlen = {}
for s in sites:
    b2 = cur_raw[s + 1]
    b3 = cur_raw[s + 2]
    assert b3 == 0x3F, f"site {s} 3rd byte not '?': {b3:02X}"
    nxt = cur_raw[s + 3] if s + 3 < n else 0
    after[nxt] = after.get(nxt, 0) + 1
print("byte AFTER the '?' (the deleted one):",
      {f"0x{k:02X}": v for k, v in sorted(after.items())})
print("  => all ASCII?", all(k < 0x80 for k in after))

# 2-byte (CJK fullwidth punct) vs 3-byte sites
b2c = {}
for s in sites:
    b2c.setdefault(cur_raw[s + 1], 0)
    b2c[cur_raw[s + 1]] += 1
print("2nd-byte histogram:", {f"0x{k:02X}": v for k, v in sorted(b2c.items())})
