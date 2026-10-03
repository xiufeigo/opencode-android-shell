param([string]$Out = 'ui', [string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s $Serial shell uiautomator dump /sdcard/tb_ui.xml 2>&1 | Out-Null
& $adb -s $Serial pull /sdcard/tb_ui.xml "research/v40_verify/tB/$Out.xml" 2>&1 | Out-Null
Write-Output "research/v40_verify/tB/$Out.xml"
