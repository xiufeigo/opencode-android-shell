#!/bin/bash
# standalone: bash research/_v51_do.sh tap X Y | text "..." | key CODE | ui [filter]
export MSYS_NO_PATHCONV=1
ADB="C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
S=emulator-5560
c=$1; shift
case "$c" in
  tap) "$ADB" -s $S shell input tap "$1" "$2" ;;
  swip) "$ADB" -s $S shell input swipe "$1" "$2" "$3" "$4" "${5:-300}" ;;
  text) "$ADB" -s $S shell input text "$1" ;;
  key) "$ADB" -s $S shell input keyevent "$1" ;;
  ui) powershell.exe -NoProfile -File research/_uix.ps1 -Serial $S -Filter "${1:-}" ;;
  *) echo "unknown: $c"; exit 1 ;;
esac
