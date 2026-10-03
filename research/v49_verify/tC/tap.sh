#!/bin/bash
export MSYS_NO_PATHCONV=1
S=${3:-emulator-5558}
export PATH="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools:$PATH"
adb -s $S shell input tap $1 $2
echo "tapped $1,$2"
