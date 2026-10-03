#!/bin/bash
# usage: _v43shot.sh name  — capture on emulator-5558 into cwd (expects cwd=this dir)
export MSYS_NO_PATHCONV=1
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
S=emulator-5558
$ADB -s $S shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 0.3
$ADB -s $S shell screencap -p /sdcard/$1.png
$ADB -s $S pull /sdcard/$1.png "./$1.png" >/dev/null && echo "ok ./$1.png"
