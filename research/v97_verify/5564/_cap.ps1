# capture helper: adb screencap -p + adb pull  (NO exec-out)
param([string]$Name)
$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5564"
& $adb -s emulator-5564 shell screencap -p /sdcard/_s.png
& $adb -s emulator-5564 pull /sdcard/_s.png (Join-Path $dir $Name) | Out-Null
Write-Output "SHOT: $Name"
