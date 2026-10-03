param([Parameter(Mandatory=$true)][string]$Name)
$ErrorActionPreference='Stop'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5562"
$dir="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98"
# screenshot: screencap -p + pull (NEVER exec-out)
& $adb -s $S shell screencap -p /sdcard/$Name.png | Out-Null
& $adb -s $S pull /sdcard/$Name.png "$dir\$Name.png" | Out-Null
& $adb -s $S shell rm /sdcard/$Name.png | Out-Null
# ui dump
& $adb -s $S shell uiautomator dump /sdcard/$Name.xml | Out-Null
& $adb -s $S pull /sdcard/$Name.xml "$dir\$Name.xml" | Out-Null
& $adb -s $S shell rm /sdcard/$Name.xml | Out-Null
"captured: $Name"
