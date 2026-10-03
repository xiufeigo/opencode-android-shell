#!/bin/bash
# usage: . research/_v51_shot.sh <name>
export MSYS_NO_PATHCONV=1
ADB="C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
S=emulator-5560
D=research/v51_verify/tD
mkdir -p $D
"$ADB" -s $S shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 0.5
"$ADB" -s $S shell screencap -p /sdcard/$1.png >/dev/null 2>&1
"$ADB" -s $S pull /sdcard/$1.png "$D/$1.png" >/dev/null 2>&1
if [ -f "$D/$1.png" ]; then echo "SHOT $D/$1.png"; else echo "SHOT FAILED $1"; fi
