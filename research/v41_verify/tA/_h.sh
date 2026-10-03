export PATH="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools:$PATH"
export MSYS_NO_PATHCONV=1
R="$PWD/research/v41_verify/tA"
S=emulator-5556
shot(){ adb -s $S shell input keyevent KEYCODE_WAKEUP >/dev/null; sleep 0.4; adb -s $S exec-out screencap -p > "$R/$1.png"; echo "shot $1"; }
dump(){ adb -s $S shell uiautomator dump /sdcard/v41.xml >/dev/null 2>&1; adb -s $S exec-out cat /sdcard/v41.xml > "$R/$1.xml"; echo "dump $1 $(wc -c < "$R/$1.xml")"; }
nodes(){ python -c "
import re
x=open(r'$R/$1.xml',encoding='utf-8').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*?bounds=\"(\[[0-9,\]\[]+)\"',x):
    t=m.group(1)
    if t.strip(): print(repr(t), m.group(2))
"; }
tap(){ adb -s $S shell input tap $1 $2; }
key(){ adb -s $S shell input keyevent $1; }
sw(){ adb -s $S shell input swipe $1 $2 $3 $4 ${5:-300}; }
