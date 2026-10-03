#!/bin/bash
export MSYS_NO_PATHCONV=1
# usage: shot.sh <name> [serial]
S=${2:-emulator-5558}
export PATH="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools:$PATH"
D=research/v47_verify/tC
adb -s $S shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 0.4
adb -s $S shell screencap -p /sdcard/$1.png >/dev/null 2>&1
adb -s $S pull /sdcard/$1.png $D/$1.png >/dev/null 2>&1
echo "$D/$1.png"
