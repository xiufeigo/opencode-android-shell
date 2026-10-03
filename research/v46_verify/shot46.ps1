param(
    [string]$Serial = 'emulator-5554',
    [Parameter(Mandatory = $true)][string]$Name
)
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Milliseconds 300
& $adb -s $Serial shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s $Serial pull /sdcard/$Name.png "research/v46_verify/tE/$Name.png" 2>&1 | Out-Null
Write-Output "saved research/v46_verify/tE/$Name.png"
