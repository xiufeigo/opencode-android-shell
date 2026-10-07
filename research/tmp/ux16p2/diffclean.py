import difflib

clean = open(r"research\tmp\ux16p2\blob-cd088b3.html", "rb").read().decode("utf-8").splitlines()
cur = open(r"webapp\src\main\assets\www\index.html", "rb").read().decode("utf-8", errors="replace").splitlines()
norm = [l.replace("\ufffd?", "\u25a1") for l in cur]

sm = difflib.SequenceMatcher(None, clean, norm, autojunk=False)
out = []
for tag, i1, i2, j1, j2 in sm.get_opcodes():
    if tag == "equal":
        continue
    out.append(f"===== {tag}  clean[{i1+1}:{i2}]  current[{j1+1}:{j2}] =====")
    out += [f"  CLEAN  |{l}" for l in clean[i1:i2]]
    out += [f"  CURR   |{l}" for l in norm[j1:j2]]
open(r"research\tmp\ux16p2\diff-clean.txt", "w", encoding="utf-8").write("\n".join(out))
print("ok, blocks:", sum(1 for x in out if x.startswith("=====")))
