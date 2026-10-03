#!/bin/bash
# usage: act.sh tap X Y | act.sh shot NAME | act.sh key CODE | act.sh text "s" | act.sh dump [filter]
export MSYS_NO_PATHCONV=1
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
S=emulator-5558
D=$(dirname "$0")
case "$1" in
 tap) $ADB -s $S shell input tap $2 $3; sleep "${4:-1.2}";;
 shot) $ADB -s $S shell input keyevent KEYCODE_WAKEUP >/dev/null; sleep 0.4; $ADB -s $S shell screencap -p /sdcard/$2.png; $ADB -s $S pull /sdcard/$2.png $D/tC/$2.png >/dev/null; echo $D/tC/$2.png;;
 key) $ADB -s $S shell input keyevent $2; sleep 0.6;;
 del) for i in $(seq 1 ${2:-20}); do $ADB -s $S shell input keyevent KEYCODE_DEL; done;;
 text) $ADB -s $S shell input text "$2"; sleep 0.5;;
 dump) PYTHONIOENCODING=utf-8 python $D/dump.py ${2:-};;
 keys) shift 2; for k in "$@"; do $ADB -s $S shell input keyevent $k; sleep 0.12; done; sleep 0.4;;
esac
