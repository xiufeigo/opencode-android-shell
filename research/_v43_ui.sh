#!/bin/bash
export PATH=$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools
adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb -s emulator-5554 pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
cat /tmp/ui.xml
