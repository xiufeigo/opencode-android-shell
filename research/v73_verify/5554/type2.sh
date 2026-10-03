#!/bin/bash
# slow char-by-char typing, space -> %s
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
D="-s emulator-5554"
for (( i=0; i<${#1}; i++ )); do
  c="${1:$i:1}"
  [ "$c" = " " ] && c='%s'
  $ADB $D shell input text "$c" >/dev/null 2>&1
  sleep 1.5
done
