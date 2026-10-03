#!/bin/bash
# helper for E2ECHK5560 session on emulator-5554 (Pixel_10_Pro_XL AVD, density 480)
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
D=emulator-5554
S=C:/sensorsdata/main/program/opencode-android-shell/research/v72_verify/5560
shot() { # shot <name>
  $ADB -s $D exec-out screencap -p > "$S/$1.png" 2>/dev/null
}
dump() { # dump <name>
  $ADB -s $D shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  $ADB -s $D pull /sdcard/ui.xml "$S/$1.xml" >/dev/null 2>&1
}
find_text() { # find_text <file.xml> <pattern> -> prints nodes
  grep -o "text=\"[^\"]*$2[^\"]*\"[^>]*bounds=\"[^\"]*\"" "$1" | head -5
}
