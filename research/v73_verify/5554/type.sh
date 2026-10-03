#!/bin/bash
# usage: type.sh "text"  -> types char-by-char, slow, spaces as %s
ADB=/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe
D="-s emulator-5554"
for (( i=0; i<${#1}; i++ )); do
  c="${1:$i:1}"
  case "$c" in
    " ") c='%s' ;;
    "'") c="\'" ;;
  esac
  $ADB $D shell input text "$c" >/dev/null 2>&1
  sleep 0.45
done
