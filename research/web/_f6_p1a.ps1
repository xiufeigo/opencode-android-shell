$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = "emulator-6170"
$pkg = "com.xiufeigo.opencodeshell.web.debug"
$W = "C:\sensorsdata\main\program\opencode-android-shell\research\web"

# make `input text` trustworthy: disable the latin IME (V8 2.2)
& $adb -s $s shell ime disable com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME 2>&1 | Out-String
& $adb -s $s shell settings put secure show_ime_with_hard_keyboard 0 2>&1 | Out-String

& $adb -s $s shell am force-stop $pkg
& $adb -s $s shell pm clear $pkg | Out-String
Start-Sleep -Seconds 2

& $adb -s $s logcat -c
& $adb -s $s shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" 2>&1 | Out-String
Start-Sleep -Seconds 6

& $adb -s $s shell uiautomator dump /sdcard/f6_setup.xml 2>&1 | Out-String
& $adb -s $s pull /sdcard/f6_setup.xml "$W\_f6_setup.xml" 2>&1 | Out-String
& $adb -s $s exec-out screencap -p > "$W\_f6_path1_setup.png"
Write-Output "DUMP_DONE"
