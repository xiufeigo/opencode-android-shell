. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_map.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo
$null = Cap "S_E1check"
Say "formOpen=$(FormOpen) inServers=$(InServers)"
ShowM "E1check"
DumpT "E1CHECK"

# persisted store truth
$store = & $global:ADB -s emulator-5554 shell "run-as com.xiufeigo.opencodeshell cat shared_prefs/*ssh*.xml 2>/dev/null"
Say "--- store ---"
$store | ForEach-Object { if ($_) { Write-Host $_ } }
