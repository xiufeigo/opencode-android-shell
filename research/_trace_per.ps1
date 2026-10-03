$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$Serial = 'emulator-5562'

# Background getevent on each event device, separate files
foreach ($d in @('event0','event1','event2','event3')) {
    $p = Start-Process -FilePath $adb -ArgumentList @('-s', $Serial, 'shell', "getevent -lt /dev/input/$d > /sdcard/ge_$d.txt 2>&1") -PassThru -NoNewWindow
}
Start-Sleep -Milliseconds 800
& $adb -s $Serial shell input tap 583 631
Start-Sleep -Milliseconds 1200
foreach ($d in @('event0','event1','event2','event3')) {
    Write-Output "--- /dev/input/$d ---"
    & $adb -s $Serial shell "cat /sdcard/ge_$d.txt"
}
Get-Process | Where-Object { $_.CommandLine -like '*getevent*' -and $_.Name -eq 'adb' } | Stop-Process -Force -ErrorAction SilentlyContinue
Write-Output '---END---'
