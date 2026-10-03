export PATH="$PATH:/c/Users/xiufe/AppData/Local/Android/Sdk/platform-tools"
export MSYS_NO_PATHCONV=1
D=emulator-5554
dump(){ adb -s $D shell uiautomator dump /sdcard/d.xml >/dev/null 2>&1; adb -s $D pull /sdcard/d.xml /tmp/d.xml >/dev/null 2>&1; }
shot(){ powershell -File research/v56_verify/tE/shot.ps1 -Name "$1" >/dev/null; }
tap(){ adb -s $D shell input tap $1 $2; }
