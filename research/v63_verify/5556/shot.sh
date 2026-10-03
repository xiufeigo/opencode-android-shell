#!/bin/bash
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
D=/c/sensorsdata/main/program/opencode-android-shell/research/v63_verify/5556
n=$1
$ADB -s emulator-5556 exec-out screencap -p > $D/$n.png
echo $D/$n.png
