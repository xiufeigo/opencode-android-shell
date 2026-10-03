$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$Serial = 'emulator-5562'

# Background getevent on all event devices
$proc = Start-Process -FilePath $adb -ArgumentList @('-s', $Serial, 'shell', 'getevent -lt /dev/input/event0 /dev/input/event1 /dev/input/event2 /dev/input/event3 > /sdcard/geall.txt 2>&1') -PassThru -NoNewWindow
Start-Sleep -Milliseconds 800
& $adb -s $Serial shell input tap 583 631
Start-Sleep -Milliseconds 1500
& $adb -s $Serial shell 'cat /sdcard/geall.txt | head -80'
Write-Output '---END---'
