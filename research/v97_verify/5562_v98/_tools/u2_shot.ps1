param([Parameter(Mandatory=$true)][string]$Name)
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5562"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98"
& $adb -s $S shell screencap -p /sdcard/u2p.png | Out-Null
& $adb -s $S pull /sdcard/u2p.png "$d\$Name.png" | Out-Null
& $adb -s $S shell rm /sdcard/u2p.png | Out-Null
"shot: $Name"
