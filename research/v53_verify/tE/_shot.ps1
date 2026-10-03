param([string]$Serial='emulator-5554',[string]$Name='shot')
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Milliseconds 300
& $adb -s $Serial shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s $Serial pull /sdcard/$Name.png research/v53_verify/tE/$Name.png 2>&1 | Out-Null
Write-Output "research/v53_verify/tE/$Name.png"
