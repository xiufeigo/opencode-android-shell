#!/bin/bash
export PYTHONIOENCODING=utf-8
export PATH="$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools"
S=emulator-5560
adb -s $S shell rm -f /sdcard/d.xml
out=$(adb -s $S shell uiautomator dump /sdcard/d.xml 2>&1)
echo "$out" | grep -q "dumped to" || { echo "DUMP FAILED: $out"; exit 1; }
MSYS_NO_PATHCONV=1 adb -s $S pull /sdcard/d.xml /tmp/d.xml >/dev/null
python -c "
import re
s=open('/tmp/d.xml',encoding='utf-8').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?content-desc=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',s):
    t,c,x1,y1,x2,y2=m.groups()
    if t or c:
        print(f'{(int(x1)+int(x2))//2},{(int(y1)+int(y2))//2}  t={t!r} d={c!r}')
"
