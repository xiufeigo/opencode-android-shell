. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_entry.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo
$null = Cap "C0_state"
$vp = Viewport
if ($vp) { Say ("VIEWPORT [{0},{1}][{2},{3}]" -f $vp['l'],$vp['t'],$vp['r'],$vp['b']) }
Say "formOpen=$(FormOpen) inServers=$(InServers)"
Say "--- ALL text nodes in the panel band (y 600..2000) ---"
foreach ($n in (Nodes)) {
    $t = TxtOf $n
    if (-not $t) { continue }
    $b = Bnd $n
    if (-not $b) { continue }
    if ($b['t'] -lt 600 -or $b['t'] -gt 2000) { continue }
    Write-Host ("  [{0},{1}][{2},{3}] en={4} cl={5} | {6}" -f $b['l'],$b['t'],$b['r'],$b['b'],$n.GetAttribute("enabled"),$n.GetAttribute("clickable"),$t)
}
Say "--- raw count of 'Server actions' ---"
$cnt = 0
foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Server actions") { $cnt++ } }
Say "SERVER_ACTIONS_COUNT=$cnt"
Say "--- store ---"
& $global:ADB -s emulator-5554 shell "run-as com.xiufeigo.opencodeshell sh -c 'ls shared_prefs/'" 
