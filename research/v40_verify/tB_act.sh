#!/bin/bash
export MSYS_NO_PATHCONV=1
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
S=emulator-5554
D=$(dirname "$0")
case "$1" in
 tap) $ADB -s $S shell input tap $2 $3; sleep "${4:-1.2}";;
 shot) $ADB -s $S shell input keyevent KEYCODE_WAKEUP >/dev/null; sleep 0.4; $ADB -s $S shell screencap -p /sdcard/tb_$2.png; $ADB -s $S pull /sdcard/tb_$2.png $D/tB/tb_$2.png >/dev/null; echo $D/tB/tb_$2.png;;
 key) $ADB -s $S shell input keyevent $2; sleep 0.6;;
 del) for i in $(seq 1 ${2:-20}); do $ADB -s $S shell input keyevent KEYCODE_DEL; done;;
 text) $ADB -s $S shell input text "$2"; sleep 0.5;;
 dump) $ADB -s $S shell uiautomator dump /sdcard/tb_$2.xml >/dev/null; $ADB -s $S pull /sdcard/tb_$2.xml $D/tB/tb_$2.xml >/dev/null; echo $D/tB/tb_$2.xml;;
 keys) shift 2; for k in "$@"; do $ADB -s $S shell input keyevent $k; sleep 0.12; done; sleep 0.4;;
 swipe) $ADB -s $S shell input swipe $2 $3 $4 $5 ${6:-300}; sleep 1;;
esac
