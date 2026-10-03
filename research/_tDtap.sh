#!/bin/bash
export PATH="$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools"
adb -s emulator-5560 shell input tap $1 $2
