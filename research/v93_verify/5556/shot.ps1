param([string]$Name)
# Screencap to PNG (binary-safe via cmd /c redirect)
$ErrorActionPreference = 'Stop'
$dir = Split-Path $MyInvocation.MyCommand.Path
$out = Join-Path $dir "$Name.png"
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
cmd /c "`"$adb`" -s emulator-5556 exec-out screencap -p > `"$out`""
Start-Sleep -Milliseconds 400
$len = (Get-Item $out).Length
"SHOT $Name -> $out  bytes=$len"
