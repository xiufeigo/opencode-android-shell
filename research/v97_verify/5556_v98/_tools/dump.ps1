param([string]$Tag)
$ErrorActionPreference='Stop'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"
$dev="/sdcard/_c"
& $adb -s emulator-5556 shell uiautomator dump "$dev/$Tag.xml" | Out-Null
& $adb -s emulator-5556 pull "$dev/$Tag.xml" "$d/$Tag.xml" | Out-Null
Write-Output "DUMPED $Tag"
