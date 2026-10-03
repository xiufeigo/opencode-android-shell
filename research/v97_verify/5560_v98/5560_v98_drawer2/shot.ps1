param([string]$Tag = "s")
$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98\5560_v98_drawer2\evidence"
& $adb -s emulator-5560 shell screencap -p /sdcard/$Tag.png
& $adb -s emulator-5560 pull /sdcard/$Tag.png "$ev\$Tag.png" | Out-Null
"saved: evidence/$Tag.png  ($((Get-Item "$ev\$Tag.png").Length) bytes)"
