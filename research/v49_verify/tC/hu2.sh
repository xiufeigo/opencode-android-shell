#!/bin/bash
# hu2.sh — tight heads-up sampler (~0.9s cadence).
#
# Two independent probes per tick, because a ~8s banner can slip between coarse
# screenshots:
#   1. screencap  — ground truth, is the banner actually painted on screen
#   2. SystemUI's heads-up window — cheap, catches even a partial flash
# Also records the y of the app's top row in the UI dump's first frames so the
# 300px displacement v48 caused can be measured rather than eyeballed.
#
# Usage: bash hu2.sh <n_ticks> [prefix]
export MSYS_NO_PATHCONV=1
S=emulator-5558
export PATH="/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools:$PATH"
D=C:/sensorsdata/main/program/opencode-android-shell/research/v49_verify/tC
N=${1:-40}
P=${2:-hu2}

for i in $(seq -w 0 $((N-1))); do
  T=$(date +%H:%M:%S)
  HU=$(adb -s $S shell dumpsys window windows 2>/dev/null | grep -ci headsup)
  adb -s $S shell screencap -p /sdcard/$P$i.png >/dev/null 2>&1
  adb -s $S pull /sdcard/$P$i.png $D/$P$i.png >/dev/null 2>&1
  echo "$T $P$i headsupWin=$HU"
done
