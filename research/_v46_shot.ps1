param(
    [string]$Serial = 'emulator-5558',
    [Parameter(Mandatory = $true)][string]$Name
)
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dir = Join-Path $PSScriptRoot 'v46_verify\tC'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Milliseconds 300
& $adb -s $Serial shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s $Serial pull /sdcard/$Name.png (Join-Path $dir "$Name.png") 2>&1 | Out-Null
Write-Output "saved research/v46_verify/tC/$Name.png"
