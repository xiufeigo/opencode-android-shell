import json
import sys

PATH = "/home/xiufeigo/.config/opencode/opencode.json"
OLD = '  "$schema": "https://opencode.ai/config.json",'
NEW = (
    '  "$schema": "https://opencode.ai/config.json",\n'
    '  "permission": {\n'
    '    "bash": {\n'
    '      "*": "ask"\n'
    '    }\n'
    '  },'
)

mode = sys.argv[1]
s = open(PATH, encoding="utf-8").read()

if mode == "apply":
    if '"*": "ask"' in s:
        print("already patched")
        sys.exit(0)
    assert s.count(OLD) == 1, ("old anchor count", s.count(OLD))
    s = s.replace(OLD, NEW, 1)
    open(PATH, "w", encoding="utf-8").write(s)
    print("patched")
elif mode == "restore":
    bak = open("/home/xiufeigo/.config/opencode/opencode.json.v17bak", encoding="utf-8").read()
    open(PATH, "w", encoding="utf-8").write(bak)
    print("restored from backup")
    s = bak
else:
    sys.exit("bad mode")

cfg = json.load(open(PATH, encoding="utf-8"))
print("valid json; permission =", json.dumps(cfg.get("permission")))
