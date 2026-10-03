#!/bin/bash
export MSYS_NO_PATHCONV=1
export PATH="$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools"
N=$1
D=/c/sensorsdata/main/program/opencode-android-shell/research/v48_verify/tD
adb -s emulator-5560 shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 0.4
adb -s emulator-5560 shell screencap -p /sdcard/$N.png >/dev/null 2>&1
(cd $D && adb -s emulator-5560 pull /sdcard/$N.png ./$N.png >/dev/null 2>&1)
echo $D/$N.png
