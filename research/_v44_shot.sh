#!/bin/bash
export MSYS_NO_PATHCONV=1
N=$1
export PATH=$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools
D=C:/sensorsdata/main/program/opencode-android-shell/research/v44_verify/tC
adb -s emulator-5558 shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 0.3
adb -s emulator-5558 shell screencap -p /sdcard/$N >/dev/null
adb -s emulator-5558 pull /sdcard/$N "$D/$N" >/dev/null
echo "$D/$N"
