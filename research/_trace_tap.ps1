$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$Serial = 'emulator-5562'

# start background getevent on the tablet, output to /sdcard
$proc = Start-Process -FilePath $adb -ArgumentList @('-s', $Serial, 'shell', 'getevent -lt /dev/input/event2 > /sdcard/ge.txt 2>&1') -PassThru -NoNewWindow
Start-Sleep -Milliseconds 600
& $adb -s $Serial shell input tap 583 631
Start-Sleep -Milliseconds 800
& $adb -s $Serial shell 'cat /sdcard/ge.txt'
Get-Process | Where-Object { $_.Name -eq 'adb' -and $_.CommandLine -like '*getevent*' } | Stop-Process -Force -ErrorAction SilentlyContinue
Write-Output '---END---'
