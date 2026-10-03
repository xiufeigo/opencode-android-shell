#!/usr/bin/env bash
# v18 shot helper: screencap from emulator-5554 into research/v18_verify/panel/
# Notes learned this round:
#  - Git Bash `>` redirect corrupts binary (CRLF translation) -> screencap on device + pull.
#  - MSYS_NO_PATHCONV=1 required or adb rewrites /sdcard/... into a Windows path.
#  - Pixel 10 Pro Fold exposes 2 displays; the app runs on display 0.
#    Pass "both" to also capture display 1 (suffix _d1).
set -e
export MSYS_NO_PATHCONV=1
ADB="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SER="${SER:-emulator-5554}"
DIR=$(cygpath -w "$(cd "$(dirname "$0")" && pwd)/panel")
D0=4619827259835644672
D1=4619827551948147201

shot1() { # <name> <displayId>
  "$ADB" -s "$SER" shell "screencap -d $2 -p /sdcard/v18shot.png" 2>/dev/null
  "$ADB" -s "$SER" pull /sdcard/v18shot.png "$DIR/$1.png" > /dev/null
  echo "saved research/v18_verify/panel/$1.png"
}

case "${2:-d0}" in
  both)
    shot1 "$1" "$D0"
    shot1 "${1}_d1" "$D1"
    ;;
  d1) shot1 "$1" "$D1" ;;
  *)  shot1 "$1" "$D0" ;;
esac
