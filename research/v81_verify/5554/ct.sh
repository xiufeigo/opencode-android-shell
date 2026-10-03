#!/bin/bash
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
MSYS_NO_PATHCONV=1 $ADB -s emulator-5554 shell uiautomator dump /sdcard/v81.xml >/dev/null 2>&1
MSYS_NO_PATHCONV=1 $ADB -s emulator-5554 exec-out cat /sdcard/v81.xml 2>/dev/null | tr '<' '\n<' | grep 'android.widget.EditText' | head -1 | sed -E 's/.* text="([^"]*)".*/\1/'
