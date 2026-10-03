#!/bin/bash
export MSYS_NO_PATHCONV=1
N=$1
export PATH=$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools
D=C:/sensorsdata/main/program/opencode-android-shell/research/v44_verify/tE
mkdir -p "$D"
adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 0.3
adb -s emulator-5554 shell screencap -p /sdcard/$N.png >/dev/null
adb -s emulator-5554 pull /sdcard/$N.png "$D/$N.png" >/dev/null
echo "$D/$N.png"
