param([string]$Tag)
$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
$sd  = "/sdcard/d3"
& $adb -s emulator-5554 shell "rm -rf $sd; mkdir -p $sd" | Out-Null
# a11y dump
& $adb -s emulator-5554 shell "uiautomator dump $sd/$Tag.xml" | Out-Null
& $adb -s emulator-5554 pull "$sd/$Tag.xml" "$dir\$Tag.xml" 2>&1 | Out-Null
# screenshot: screencap -p + pull (exec-out forbidden)
& $adb -s emulator-5554 shell "screencap -p $sd/$Tag.png" | Out-Null
& $adb -s emulator-5554 pull "$sd/$Tag.png" "$dir\$Tag.png" 2>&1 | Out-Null
Write-Output "OK $Tag"
