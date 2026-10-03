source "$(dirname "$0")/env.sh"
export MSYS_NO_PATHCONV=1
"$ADB" -s $D shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 0.4
"$ADB" -s $D shell screencap -p /sdcard/$1.png >/dev/null 2>&1
"$ADB" -s $D pull /sdcard/$1.png "$SHOTDIR/$1.png" >/dev/null 2>&1
ls -la "$SHOTDIR/$1.png"
