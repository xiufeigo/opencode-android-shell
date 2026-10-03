#!/bin/bash
# hu3.sh — send a prompt, then sample the heads-up at ~0.9s cadence.
#
# Stays in the SESSION view (this is the state v48's v48_31_hunotify.png was
# captured in) so the comparison against the 300px push is like-for-like, and
# leaves the keyboard up because a heads-up displacing the IME is the most
# obvious way for the v48 bug to still be visible.
#
# Usage: bash hu3.sh <n_ticks> <send_x> <send_y>
export MSYS_NO_PATHCONV=1
S=emulator-5558
export PATH="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools:$PATH"
D=C:/sensorsdata/main/program/opencode-android-shell/research/v49_verify/tC
N=${1:-40}
SX=${2:-990}
SY=${3:-1462}

echo "[$(date +%H:%M:%S.%3N)] SEND ($SX,$SY)"
adb -s $S shell input tap $SX $SY
echo "[$(date +%H:%M:%S.%3N)] monitoring"
for i in $(seq -w 0 $((N-1))); do
  T=$(date +%H:%M:%S)
  HU=$(adb -s $S shell dumpsys window windows 2>/dev/null | grep -ci headsup)
  adb -s $S shell screencap -p /sdcard/hu3_$i.png >/dev/null 2>&1
  adb -s $S pull /sdcard/hu3_$i.png $D/hu3_$i.png >/dev/null 2>&1
  echo "$T hu3_$i headsupWin=$HU"
done
