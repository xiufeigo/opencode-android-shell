#!/bin/bash
# helper: every adb call runs with MSYS_NO_PATHCONV=1 so /sdcard paths survive Git Bash
export MSYS_NO_PATHCONV=1
export PATH="$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools"
A="adb -s emulator-5554"
D="research/v54_verify/tE"
