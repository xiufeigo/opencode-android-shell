#!/bin/bash
export MSYS_NO_PATHCONV=1 PYTHONIOENCODING=utf-8
S=${1:-emulator-5558}
export PATH="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools:$PATH"
adb -s $S shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
adb -s $S pull /sdcard/u.xml /tmp/u.xml >/dev/null 2>&1
python3 -c "
import re,sys
x=open('/tmp/u.xml',encoding='utf-8').read()
for m in re.finditer(r'<node[^>]*>',x):
    s=m.group(0)
    t=re.search(r'text=\"([^\"]*)\"',s); d=re.search(r'content-desc=\"([^\"]*)\"',s); b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',s)
    c=(re.search(r'class=\"([^\"]*)\"',s) or [None,''])[1]
    txt=(t.group(1) if t else '')+('|'+d.group(1) if d and d.group(1) else '')
    if not txt.strip(): continue
    bb=b.groups() if b else ('?','?','?','?')
    cx=(int(bb[0])+int(bb[2]))//2 if b else 0
    cy=(int(bb[1])+int(bb[3]))//2 if b else 0
    print(f'{cx:>5},{cy:<5} {c.split(\".\")[-1]:<12} {txt[:60]!r} {bb}')
"
