param(
    [string]$Serial = 'emulator-5562',
    [string]$Name = 'shot'
)
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
# Wake the screen before capture; this emulator falls asleep between actions
# and screencap captures a black framebuffer then.
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Milliseconds 400
& $adb -s $Serial shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s $Serial pull /sdcard/$Name.png research/v6_verify/small_desktop/$Name.png 2>&1 | Out-Null
Write-Output "saved research/v6_verify/small_desktop/$Name.png"
