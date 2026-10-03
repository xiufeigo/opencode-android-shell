param([string]$Tag)
$ErrorActionPreference='Stop'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"
$dev="/sdcard/_c"
& $adb -s emulator-5556 shell screencap -p "$dev/$Tag.png"
& $adb -s emulator-5556 pull "$dev/$Tag.png" "$d/$Tag.png" | Out-Null
Write-Output "SHOT $Tag"
