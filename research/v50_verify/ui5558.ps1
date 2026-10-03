param([switch]$Full)
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$S='emulator-5558'
if($Full){ & $adb -s $S shell uiautomator dump /sdcard/ui.xml | Out-Null; & $adb -s $S pull /sdcard/ui.xml research/v50_verify/tC/ui.xml | Out-Null; Write-Output 'research/v50_verify/tC/ui.xml' }
else { & $adb -s $S shell uiautomator dump /sdcard/ui.xml | Out-Null; & $adb -s $S shell cat /sdcard/ui.xml }
