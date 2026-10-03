#!/bin/bash
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
D="-s emulator-5554"
HERE="$(cd "$(dirname "$0")" && pwd)"
MSYS_NO_PATHCONV=1 $ADB $D shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB $D shell cat /sdcard/ui.xml > "$HERE/${1:-latest}.xml" 2>/dev/null
echo "$HERE/${1:-latest}.xml"
