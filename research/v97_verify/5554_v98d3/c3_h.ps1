. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_entry.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo
$null = Cap "H0_state"
Say "formOpen=$(FormOpen) inServers=$(InServers)"
Say "--- ALL scrollable nodes ---"
foreach ($n in (Nodes)) {
    if ($n.GetAttribute("scrollable") -ne "true") { continue }
    $b = Bnd $n
    if ($b) { Say ("  scrollable [{0},{1}][{2},{3}] class={4}" -f $b['l'],$b['t'],$b['r'],$b['b'],$n.GetAttribute("class")) }
}
Say "--- panel band texts (y 800..2000) ---"
foreach ($n in (Nodes)) {
    $t = TxtOf $n; if (-not $t) { continue }
    $b = Bnd $n; if (-not $b) { continue }
    if ($b['t'] -lt 800 -or $b['t'] -gt 2000) { continue }
    Write-Host ("   [{0},{1}][{2},{3}] en={4} cl={5} | {6}" -f $b['l'],$b['t'],$b['r'],$b['b'],$n.GetAttribute("enabled"),$n.GetAttribute("clickable"),$t)
}
# SAFETY: if a confirm bar got armed on someone else's row, cancel it
$t1 = FindN "Delete SSH server?"
if ($t1) {
    Say "!! CONFIRM BAR ARMED — cancelling (row belongs to another agent)"
    $c = FindN "Cancel"
    if ($c) { $b = Bnd $c; Say "  cancel @ $($b['cx']),$($b['cy'])"; TapXY $b['cx'] $b['cy']; Start-Sleep -Milliseconds 1200 }
}
$null = Cap "H1_after"
Say "DONE_H"
