source "$(dirname "$0")/env.sh"
"$ADB" -s $D shell input tap $1 $2 >/dev/null
echo "tap $1 $2"
