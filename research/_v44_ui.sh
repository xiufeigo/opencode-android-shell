#!/bin/bash
export MSYS_NO_PATHCONV=1
export PATH=$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools
adb -s emulator-5558 shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb -s emulator-5558 shell cat /sdcard/ui.xml
