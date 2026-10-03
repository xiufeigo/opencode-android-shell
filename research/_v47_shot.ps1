param([string]$Serial='emulator-5554',[string]$Name='tE_00')
$ErrorActionPreference='Continue'
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dir=Join-Path $PSScriptRoot 'v47_verify\tE'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Milliseconds 300
& $adb -s $Serial shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s $Serial pull /sdcard/$Name.png (Join-Path $dir "$Name.png") *>$null
"saved research/v47_verify/tE/$Name.png"
