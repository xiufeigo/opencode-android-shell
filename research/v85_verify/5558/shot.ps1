param([Parameter(Mandatory=$true)][string]$Name)
$ErrorActionPreference='Stop'
$adb='C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$dir='C:\sensorsdata\main\program\opencode-android-shell\research\v85_verify\5558'
$out=Join-Path $dir "$Name.png"
# binary-safe screencap: exec-out piped through cmd redirection
cmd /c "`"$adb`" -s emulator-5558 exec-out screencap -p > `"$out`""
if (Test-Path $out) { Write-Output "OK $out $((Get-Item $out).Length) bytes" } else { Write-Output "FAIL $out" }
