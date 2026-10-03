param([string]$Name='shot')
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$S='emulator-5558'
& $adb -s $S shell screencap -p /sdcard/$Name.png 2>&1 | Out-Null
& $adb -s $S pull /sdcard/$Name.png research/v50_verify/tC/$Name.png 2>&1 | Out-Null
Write-Output "saved research/v50_verify/tC/$Name.png"
