#!/bin/bash
# hu.sh — send a prompt, hop to Home, then sample the heads-up notification
# timeline. v48 posted a 300px-persistent banner that stayed ~60s; v49 is
# supposed to auto-dismiss in ~8s without displacing the Home layout.
#
# Usage: bash hu.sh <n_samples> <sample_interval_s>
export MSYS_NO_PATHCONV=1
S=emulator-5558
export PATH="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools:$PATH"
D=C:/sensorsdata/main/program/opencode-android-shell/research/v49_verify/tC
N=${1:-24}
IV=${2:-1}

now() { date +%H:%M:%S; }

echo "[$(now)] SEND tap"
adb -s $S shell input tap 990 2282
sleep 1
echo "[$(now)] HOME tap"
adb -s $S shell input tap 55 190

for i in $(seq -w 0 $((N-1))); do
  T=$(now)
  adb -s $S shell screencap -p /sdcard/hu_$i.png >/dev/null 2>&1
  adb -s $S pull /sdcard/hu_$i.png $D/hu_$i.png >/dev/null 2>&1
  # Active notification records for our package: pkg + when it was posted.
  NB=$(adb -s $S shell dumpsys notification --noredact 2>/dev/null \
        | grep -c "com.xiufeigo.opencodeshell")
  # Layout displacement probe: y of the first "首页" button on Home.
  Y=$(adb -s $S shell uiautomator dump /sdcard/hu.xml >/dev/null 2>&1
        adb -s $S pull /sdcard/hu.xml /tmp/hu.xml >/dev/null 2>&1
        python3 -c "
import re
try: x=open('/tmp/hu.xml',encoding='utf-8').read()
except: print('?'); raise SystemExit
m=re.search(r'content-desc=\"首页\"[^>]*bounds=\"\[(\d+),(\d+)\]',x)
print(m.group(2) if m else 'NOTFOUND')
" 2>/dev/null)
  echo "$T t=$i notif_lines=$NB homeY=$Y"
  sleep $IV
done
