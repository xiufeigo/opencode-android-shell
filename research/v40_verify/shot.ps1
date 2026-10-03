param([string]$Name = 'shot')
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$s = 'emulator-5556'
& $adb -s $s shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Milliseconds 300
& $adb -s $s shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s $s pull /sdcard/$Name.png "research/v40_verify/tA/$Name.png" 2>&1 | Out-Null
Write-Output "tA/$Name.png"
