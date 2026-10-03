param([string]$Name)
$ErrorActionPreference = 'Stop'
$dir = Split-Path $MyInvocation.MyCommand.Path
$out = Join-Path $dir "ui_$Name.xml"
$tmp = '/sdcard/_v91.xml'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $adb -s emulator-5556 shell uiautomator dump $tmp | Out-Null
& $adb -s emulator-5556 pull $tmp $out | Out-Null
"DUMP $Name -> $out  bytes=$((Get-Item $out).Length)"
