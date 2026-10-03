param([string]$Name)
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Milliseconds 300
& $adb -s emulator-5554 shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s emulator-5554 pull /sdcard/$Name.png research/v56_verify/tE/$Name.png 2>&1 | Out-Null
Write-Output "research/v56_verify/tE/$Name.png"
