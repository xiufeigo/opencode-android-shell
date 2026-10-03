param([string]$Serial = 'emulator-5556', [string]$Name = 'shot')
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dir = 'C:\sensorsdata\main\program\opencode-android-shell\research\v56_verify\tB'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Milliseconds 350
& $adb -s $Serial shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s $Serial pull /sdcard/$Name.png (Join-Path $dir "$Name.png") 2>&1 | Out-Null
Write-Output "saved tB/$Name.png"
