#!/bin/bash
export MSYS_NO_PATHCONV=1
export PATH=$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools
adb -s emulator-5554 shell uiautomator dump /sdcard/uix.xml >/dev/null 2>&1
adb -s emulator-5554 pull /sdcard/uix.xml /tmp/uix.xml >/dev/null 2>&1
cat /tmp/uix.xml
