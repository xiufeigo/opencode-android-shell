param([string]$Name = 'ui')
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$s = 'emulator-5556'
& $adb -s $s shell uiautomator dump /sdcard/$Name.xml 2>&1 | Out-Null
& $adb -s $s pull /sdcard/$Name.xml "research/v40_verify/tA/$Name.xml" 2>&1 | Out-Null
Write-Output "tA/$Name.xml"
