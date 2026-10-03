#!/bin/sh
. "$(dirname "$0")/_env.sh"
N=$1
$ADB -s $SER shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 0.4
$ADB -s $SER shell screencap -p /sdcard/$N.png >/dev/null 2>&1
$ADB -s $SER pull /sdcard/$N.png "$(pwd -W)/$N.png" >/dev/null 2>&1
echo saved $N
