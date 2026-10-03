#!/bin/bash
# usage: shot.sh <name>   -> research/v73_verify/5554/<name>.png
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
D="-s emulator-5554"
HERE="$(cd "$(dirname "$0")" && pwd)"
$ADB $D exec-out screencap -p > "$HERE/$1.png"
echo "$HERE/$1.png"
