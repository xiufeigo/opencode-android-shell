export MSYS_NO_PATHCONV=1
#!/bin/bash
ADB="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
S=emulator-5554
D="C:/sensorsdata/main/program/opencode-android-shell/research/v84_verify/5554"
shot(){ $ADB -s $S shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; sleep 0.4; $ADB -s $S shell screencap -p sdcard/$1.png >/dev/null 2>&1; $ADB -s $S pull sdcard/$1.png "$D/$1.png" >/dev/null 2>&1; echo "shot $1"; }
dump(){ $ADB -s $S shell uiautomator dump sdcard/d.xml >/dev/null 2>&1; $ADB -s $S pull sdcard/d.xml "$D/d.xml" >/dev/null 2>&1; }
texts(){ python -c "
import xml.etree.ElementTree as ET,sys
r=ET.parse('$D/d.xml').getroot()
for n in r.iter('node'):
    t=n.get('text','');d=n.get('content-desc','')
    if t or d: print((t or d)+' @'+n.get('bounds','')+' '+n.get('class',''))
"; }
tap(){ $ADB -s $S shell input tap $1 $2; }
txt(){ $ADB -s $S shell input text "$1"; }
key(){ $ADB -s $S shell input keyevent $1; }
swipe(){ $ADB -s $S shell input swipe $1 $2 $3 $4 ${5:-300}; }
