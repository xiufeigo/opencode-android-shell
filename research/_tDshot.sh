#!/bin/bash
export PATH="$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools"
S=emulator-5560
N=$1
adb -s $S shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 0.4
adb -s $S shell screencap -p /sdcard/$N.png >/dev/null 2>&1
MSYS_NO_PATHCONV=1 adb -s $S pull /sdcard/$N.png research/v40_verify/tD/$N.png >/dev/null 2>&1
echo "research/v40_verify/tD/$N.png"
