#!/bin/bash
export MSYS_NO_PATHCONV=1
export PATH="$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools"
adb -s emulator-5560 shell input tap $1 $2
