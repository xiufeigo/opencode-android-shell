param([string]$Name)
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dir = 'C:\sensorsdata\main\program\opencode-android-shell\research\v51_verify\tC'
& $adb -s emulator-5558 shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Milliseconds 300
& $adb -s emulator-5558 shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s emulator-5558 pull /sdcard/$Name.png "$dir\$Name.png" 2>&1 | Out-Null
Write-Output "saved $dir\$Name.png"
