param([string]$Serial='emulator-5560',[string]$Name='shot')
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Milliseconds 400
& $adb -s $Serial shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s $Serial pull /sdcard/$Name.png research/v49_verify/tD/$Name.png 2>&1 | Out-Null
Write-Output "research/v49_verify/tD/$Name.png"
