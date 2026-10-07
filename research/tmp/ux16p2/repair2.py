"""UX16-P2 · Repair assets/www/index.html.

Damage mechanism (measured, see analyze.py): every occurrence of
`<3-byte CJK char><one following byte>` was collapsed to
`<first 2 bytes of that char><0x3F '?'>`.
So one source character AND one following byte are lost per site (156 sites);
when the lost following byte was CR/LF the damage also joins two lines.

The byte-clean ancestor blob (cd088b3) is used as the oracle. We align at line
level, then inside every changed block we build a regex from the damaged text
in which each damage marker matches 1..3 clean characters, and require a
UNIQUE match so no guess is ever silently accepted.
"""
import re, difflib, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

MARK = "\x00"          # stands for one damage site in the damaged text
RANGE = "(.{1,3}?)"     # the site swallows the lost char + the lost following byte

clean_lines = open(r"research\tmp\ux16p2\blob-cd088b3.html", "rb").read().decode("utf-8").splitlines()
cur_lines = open(r"webapp\src\main\assets\www\index.html", "rb").read().decode("utf-8", errors="replace").splitlines()

# normalise damaged text: the decoder emits U+FFFD for the 2 surviving lead bytes
# followed by the literal '?' (0x3F). Collapse that pair into one marker.
cur_norm = [l.replace("\ufffd?", MARK) for l in cur_lines]

out = []            # built sequentially: opcode ranges may change length
sm = difflib.SequenceMatcher(None, clean_lines, cur_norm, autojunk=False)
stats = {"equal": 0, "restored": 0, "kept": 0, "unresolved": 0}
problems = []

for tag, i1, i2, j1, j2 in sm.get_opcodes():
    if tag == "equal":
        # undamaged HEAD text - keep it verbatim (cur_lines has no markers here)
        out.extend(cur_lines[j1:j2])
        stats["equal"] += j2 - j1
        continue
    cur_block = "\n".join(cur_norm[j1:j2])
    clean_block = "\n".join(clean_lines[i1:i2])
    if MARK not in cur_block:
        # a genuine content change made in HEAD - keep the current text verbatim
        out.extend(cur_lines[j1:j2])
        stats["kept"] += j2 - j1
        continue
    parts = cur_block.split(MARK)
    pat = re.compile("(?s)" + RANGE.join(re.escape(p) for p in parts))
    hits = list(pat.finditer(clean_block))
    if len(hits) == 1:
        out.extend(hits[0].group(0).split("\n"))
        stats["restored"] += j2 - j1
    else:
        stats["unresolved"] += j2 - j1
        problems.append((i1 + 1, i2, j1 + 1, j2, clean_block, cur_block))

print("stats:", stats)
for p in problems:
    print(f"### UNRESOLVED clean[{p[0]}:{p[1]}] current[{p[2]}:{p[3]}]")
    print("  CLEAN:", p[4].replace("\n", " \\n "))
    print("  CURR :", p[5].replace("\n", " \\n "))

if not problems:
    text = "\n".join(out) + "\n"
    open(r"webapp\src\main\assets\www\index.html", "w", encoding="utf-8", newline="").write(text)
    enc = text.encode("utf-8")
    bad = enc.decode("utf-8", errors="replace").count("\ufffd")
    print(f"WROTE index.html  bytes={len(enc)}  replacement_chars={bad}")
else:
    print("NOT written - unresolved blocks remain")
