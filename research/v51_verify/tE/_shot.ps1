param([string]$Name='shot')
$ErrorActionPreference='Continue'
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$d='C:\sensorsdata\main\program\opencode-android-shell\research\v51_verify\tE'
& $adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP | Out-Null
Start-Sleep -Milliseconds 300
& $adb -s emulator-5554 shell screencap -p /sdcard/$Name.png | Out-Null
& $adb -s emulator-5554 pull /sdcard/$Name.png "$d\$Name.png" 2>&1 | Out-Null
Write-Output "research/v51_verify/tE/$Name.png"
