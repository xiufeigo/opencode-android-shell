#!/bin/bash
export MSYS_NO_PATHCONV=1
S=${2:-emulator-5558}
export PATH="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools:$PATH"
D=C:/sensorsdata/main/program/opencode-android-shell/research/v49_verify/tC
adb -s $S shell screencap -p /sdcard/$1.png >/dev/null 2>&1
adb -s $S pull /sdcard/$1.png $D/$1.png 2>&1 | grep -v '^$'
echo "$D/$1.png"
