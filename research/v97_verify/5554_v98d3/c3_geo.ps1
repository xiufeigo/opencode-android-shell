. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_entry.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo
function RowsNow {
    $o = New-Object System.Collections.ArrayList
    foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Server actions") { $null = $o.Add((Bnd $n)) } }
    return @($o | Sort-Object { $_.t })
}
function Vp {
    $best = $null; $ba = 0
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("scrollable") -ne "true") { continue }
        $b = Bnd $n; if (-not $b) { continue }
        if ($b['t'] -lt 300) { continue }
        $a = ($b['r'] - $b['l']) * ($b['b'] - $b['t'])
        if ($a -gt $ba) { $ba = $a; $best = $b }
    }
    return $best
}

$null = Cap "G0_pre"
$r = RowsNow
Say "rows y=$(($r | ForEach-Object { $_.t }) -join ',')"
$vp = Vp
Say ("VIEWPORT y in [{0},{1}]" -f $vp['t'], $vp['b'])

# scroll the PANEL body (x=540) upward to bring the low rows into the viewport
for ($i = 0; $i -lt 4; $i++) {
    $r = RowsNow
    $last = $r[-1]
    if ($last['b'] -le $vp['b']) { break }
    Sh "input swipe 540 1700 540 1150 300"
    Start-Sleep -Milliseconds 800
    $vp = Vp
}
$null = Cap "G1_scrolled"
$r = RowsNow
$vp = Vp
Say ("AFTER SCROLL VIEWPORT y in [{0},{1}]  rows y=$(($r | ForEach-Object { $_.t }) -join ',')" -f $vp['t'], $vp['b'])
foreach ($n in (Nodes)) {
    $t = TxtOf $n; if (-not $t) { continue }
    $b = Bnd $n; if (-not $b) { continue }
    if ($b['t'] -lt 800 -or $b['t'] -gt 1940) { continue }
    Write-Host ("   [{0},{1}][{2},{3}] {4}" -f $b['l'],$b['t'],$b['r'],$b['b'],$t)
}

# tap the LOWEST ⋮ now inside the viewport
$r = RowsNow
$pick = $null
foreach ($b in $r) { if ($b['b'] -le $vp['b'] -and $b['b'] -gt $vp['t']) { $pick = $b } }
if (-not $pick) { Say "NO row inside viewport"; exit }
Say "PICK row ⋮ bounds=[$($pick['l']),$($pick['t'])][$($pick['r']),$($pick['b'])] (lowest in viewport)"
TapXY $pick['cx'] $pick['cy']
Start-Sleep -Milliseconds 1200
$null = Cap "G2_menu"
$del = FindN "Delete"
if ($del) {
    $b = Bnd $del
    Say "  TAP Delete @ $($b['cx']),$($b['cy'])"
    TapXY $b['cx'] $b['cy']
} else { Say "  NO Delete in menu" }
Start-Sleep -Milliseconds 1500
$null = Cap "G3_bar"
$vp = Vp
Say ("BAR-STATE VIEWPORT y in [{0},{1}]" -f $vp['t'],$vp['b'])
$t1 = FindN "Delete SSH server?"
if ($t1) { $b = Bnd $t1; Say "BAR_TITLE bounds=[$($b['l']),$($b['t'])][$($b['r']),$($b['b'])]" } else { Say "BAR_TITLE_NOT_IN_A11Y_TREE" }
foreach ($t in @("Delete SSH server?","Delete","Cancel")) {
    $n = FindN $t
    if ($n) { $b = Bnd $n; Say ("  node '{0}' [{1},{2}][{3},{4}] en={5} cl={6}" -f $t,$b['l'],$b['t'],$b['r'],$b['b'],$n.GetAttribute("enabled"),$n.GetAttribute("clickable")) }
}
Say "DONE_G"
