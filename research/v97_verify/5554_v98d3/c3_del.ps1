. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_entry.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo

# my row is the SSH-tunnel row: name 'keypass123'. Its ⋮ is the one inside the 'SSH tunnels' group.
$mine = $null
foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "keypass123") { $mine = (Bnd $n) } }
if (-not $mine) { Say "MY ROW NOT FOUND"; exit }
Say "MY ROW name 'keypass123' [$($mine['l']),$($mine['t'])][$($mine['r']),$($mine['b'])]"

# its ⋮ = the 'Server actions' closest above/below the name, inside the same group
$acts = New-Object System.Collections.ArrayList
foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Server actions") { $null = $acts.Add((Bnd $n)) } }
$acts = @($acts | Sort-Object { $_.t })
Say "all ⋮ y=$(($acts | ForEach-Object { $_.t }) -join ',')"
$target = $acts[0]          # the first = the SSH tunnels row = mine
Say "TARGET ⋮ bounds=[$($target['l']),$($target['t'])][$($target['r']),$($target['b'])]"

$null = Cap "V0_before"
Say "--- BEFORE row inventory ---"
foreach ($n in (Nodes)) {
    $t = TxtOf $n; if (-not $t) { continue }
    $b = Bnd $n; if (-not $b) { continue }
    if ($b['t'] -lt 800 -or $b['t'] -gt 1995) { continue }
    if ($t -match 'Server actions|^keypass123|^10\.0\.2\.2|^127\.0\.0\.1|Server (healthy|not reachable)|^v2|^sshtest|SSH tunnels|^Servers$|Add SSH') {
        Write-Host ("   [{0},{1}][{2},{3}] {4}" -f $b['l'],$b['t'],$b['r'],$b['b'],$t)
    }
}

TapXY $target['cx'] $target['cy']
Start-Sleep -Milliseconds 1400
$null = Cap "V1_menu"
$del = FindN "Delete"
if (-not $del) { Say "NO Delete in menu"; exit }
$db = Bnd $del
Say "  menu Delete bounds=[$($db['l']),$($db['t'])][$($db['r']),$($db['b'])] @ $($db['cx']),$($db['cy'])"
TapXY $db['cx'] $db['cy']
Start-Sleep -Milliseconds 1600
$null = Cap "V2_bar"

Say "--- confirm bar reading (panel body viewport is NOT exposed as scrollable; use panel box 800..1937) ---"
$panelT = 800; $panelB = 1937
$bar = $null
foreach ($n in (Nodes)) {
    if ((TxtOf $n) -eq "Delete SSH server?") { $bar = (Bnd $n) }
}
if ($bar) {
    Say "BAR_TITLE bounds=[$($bar['l']),$($bar['t'])][$($bar['r']),$($bar['b'])]"
    Say "PANEL_BOX_Y=[$panelT,$panelB]  => title top $($bar['t']) >= $panelT : $($bar['t'] -ge $panelT)"
} else { Say "BAR_TITLE_NOT_IN_A11Y_TREE" }
foreach ($t in @("Delete SSH server?","Delete","Cancel","Disconnect")) {
    $n = FindN $t
    if ($n) { $b = Bnd $n; Say ("  node '{0}' [{1},{2}][{3},{4}] en={5} cl={6}" -f $t,$b['l'],$b['t'],$b['r'],$b['b'],$n.GetAttribute("enabled"),$n.GetAttribute("clickable")) }
}
Say "DONE_V2"
