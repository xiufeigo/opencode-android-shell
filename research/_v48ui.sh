#!/bin/bash
export MSYS_NO_PATHCONV=1
export PATH="$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools"
N=${1:-ui}
D=/c/sensorsdata/main/program/opencode-android-shell/research/v48_verify/tD
adb -s emulator-5560 shell uiautomator dump /sdcard/$N.xml >/dev/null 2>&1
(cd $D && adb -s emulator-5560 pull /sdcard/$N.xml ./$N.xml >/dev/null 2>&1)
echo $D/$N.xml
