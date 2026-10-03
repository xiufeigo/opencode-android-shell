#!/bin/bash
# dismiss_test.sh — catch the "回复已完成" banner, then immediately tap 忽略
# (dismiss one) and confirm it does not come back.
export MSYS_NO_PATHCONV=1
S=emulator-5558
export PATH="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools:$PATH"
D=C:/sensorsdata/main/program/opencode-android-shell/research/v49_verify/tC
W=${1:-240}

probe() {  # echoes the y of the 首页 button (127 = no banner)
  adb -s $S shell uiautomator dump /sdcard/p.xml >/dev/null 2>&1
  adb -s $S pull /sdcard/p.xml C:/Windows/Temp/p.xml >/dev/null 2>&1
  python3 -c "
import re
try: x=open('C:/Windows/Temp/p.xml',encoding='utf-8').read()
except: print('?'); raise SystemExit
m=re.search(r'content-desc=\"首页\"[^>]*bounds=\"\[(\d+),(\d+)\]',x)
print(m.group(2) if m else 'NONE')
"
}

find_btn() {  # echo "x y" of the 忽略 button, or empty
  python3 -c "
import re
try: x=open('C:/Windows/Temp/p.xml',encoding='utf-8').read()
except: raise SystemExit
for m in re.finditer(r'<node[^>]*>',x):
    s=m.group(0)
    t=re.search(r'text=\"([^\"]*)\"',s)
    if not t or t.group(1) != '忽略': continue
    b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',s)
    if not b: continue
    g=list(map(int,b.groups()))
    print((g[0]+g[2])//2,(g[1]+g[3])//2)
    break
"
}

END=$(( $(date +%s) + W ))
while [ $(date +%s) -lt $END ]; do
  Y=$(probe)
  if [ "$Y" != "127" ] && [ "$Y" != "NONE" ]; then
    N=dis_$(date +%H%M%S)
    adb -s $S shell screencap -p /sdcard/$N.png >/dev/null 2>&1
    adb -s $S pull /sdcard/$N.png $D/$N.png >/dev/null 2>&1
    echo "$(date +%H:%M:%S) banner up HOMEy=$Y -> $N.png"
    B=$(find_btn)
    echo "  忽略 button at: '$B'"
    if [ -n "$B" ]; then
      adb -s $S shell input tap $B
      echo "  $(date +%H:%M:%S) tapped 忽略"
      sleep 3
      echo "  after dismiss HOMEy=$(probe)"
      sleep 12
      echo "  +12s later   HOMEy=$(probe)"
    fi
    exit 0
  fi
  echo "$(date +%H:%M:%S) watching HOMEy=$Y"
  sleep 3
done
echo "no banner within ${W}s"
