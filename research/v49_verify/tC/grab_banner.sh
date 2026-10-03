#!/bin/bash
# grab_banner.sh — wait for the "回复已完成" banner to push the layout down,
# then screenshot it immediately. Runs tight because the banner only lives ~8s.
export MSYS_NO_PATHCONV=1
S=emulator-5558
export PATH="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools:$PATH"
D=C:/sensorsdata/main/program/opencode-android-shell/research/v49_verify/tC
W=${1:-120}   # seconds to watch

END=$(( $(date +%s) + W ))
while [ $(date +%s) -lt $END ]; do
  adb -s $S shell screencap -p /sdcard/probe.png >/dev/null 2>&1
  adb -s $S pull /sdcard/probe.png $D/_probe.png >/dev/null 2>&1
  # Top-of-screen pixel check is fragile; instead ask SystemUI whether an
  # app-supplied banner row is present via the accessibility dump.
  HIT=$(python3 -c "
import zlib,struct,sys
# cheap: just look for the literal banner glyph band by file size delta is useless;
# use uiautomator instead below
" 2>/dev/null)
  Y=$(adb -s $S shell uiautomator dump /sdcard/g.xml >/dev/null 2>&1
      adb -s $S pull /sdcard/g.xml /tmp/g.xml >/dev/null 2>&1
      python3 -c "
import re
try: x=open('/tmp/g.xml',encoding='utf-8').read()
except: print('?'); raise SystemExit
m=re.search(r'content-desc=\"首页\"[^>]*bounds=\"\[(\d+),(\d+)\]',x)
print(m.group(2) if m else 'NONE')
" 2>/dev/null)
  if [ "$Y" != "127" ] && [ "$Y" != "NONE" ] && [ -n "$Y" ]; then
    N=ban_$(date +%H%M%S)
    adb -s $S shell screencap -p /sdcard/$N.png >/dev/null 2>&1
    adb -s $S pull /sdcard/$N.png $D/$N.png >/dev/null 2>&1
    echo "BANNER CAUGHT at $(date +%H:%M:%S) HOMEy=$Y -> $N.png"
    python3 -c "
import re
x=open('/tmp/g.xml',encoding='utf-8').read()
for m in re.finditer(r'<node[^>]*>',x):
    s=m.group(0)
    for k in ('text','content-desc'):
        v=re.search(k+r'=\"([^\"]*)\"',s)
        if v and v.group(1) and ('ses_' in v.group(1) or '回复' in v.group(1) or '忽略' in v.group(1)):
            b=re.search(r'bounds=\"([^\"]*)\"',s)
            print('  ',k,'=',repr(v.group(1)),b.group(1) if b else '')
"
    exit 0
  fi
  echo "watch $(date +%H:%M:%S) HOMEy=$Y"
done
echo "no banner within ${W}s"
