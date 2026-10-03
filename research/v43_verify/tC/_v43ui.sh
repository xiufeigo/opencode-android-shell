#!/bin/bash
# usage: _v43ui.sh  — dump UI text (+bounds) to stdout
export MSYS_NO_PATHCONV=1
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
S=emulator-5558
$ADB -s $S shell uiautomator dump /sdcard/d.xml >/dev/null 2>&1
$ADB -s $S pull /sdcard/d.xml /tmp/d.xml >/dev/null 2>&1
export PYTHONIOENCODING=utf-8
python - "$@" <<'EOF'
import sys,re
d=open('/tmp/d.xml',encoding='utf-8').read()
for m in re.finditer(r'<node[^>]*>',d):
    s=m.group(0)
    t=re.search(r'text="([^"]*)"',s)
    c=re.search(r'content-desc="([^"]*)"',s)
    b=re.search(r'bounds="([^"]*)"',s)
    cls=re.search(r'class="([^"]*)"',s)
    rid=re.search(r'resource-id="([^"]*)"',s)
    t=t.group(1) if t else ''; c=c.group(1) if c else ''
    if not t and not c: continue
    print(f"{(t or c)[:60]!r} | cls={cls.group(1).split('.')[-1] if cls else ''} | id={(rid.group(1).split('/')[-1] if rid and rid.group(1) else '')} | {b.group(1) if b else ''}")
EOF
