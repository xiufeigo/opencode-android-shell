. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_entry.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo
$null = Cap "P0_card"

# the row-level password card's own field + button, parsed from THIS dump
$ff = New-Object System.Collections.ArrayList
foreach ($n in (Nodes)) {
    if ($n.GetAttribute("class") -notlike "*EditText*") { continue }
    $b = Bnd $n; if (-not $b) { continue }
    if ($b['l'] -lt 130 -or $b['l'] -gt 150) { continue }
    $null = $ff.Add([pscustomobject]@{ Txt = $n.GetAttribute("text"); Y = $b['t']; b = $b })
}
Say "card fields:"
foreach ($f in $ff) { Say ("  Y={0} [{1},{2}][{3},{4}] '{5}'" -f $f.Y,$f.b['l'],$f.b['t'],$f.b['r'],$f.b['b'],$f.Txt) }
if ($ff.Count -ge 1) {
    $t = $ff[0].b
    Say "  tap card password field @ $($t['cx']),$($t['cy'])"
    TapXY $t['cx'] $t['cy']
    Start-Sleep -Milliseconds 700
    ClearET 40
    TypeS "keypass123"
    Start-Sleep -Milliseconds 500
}
$null = Cap "P1_typed"
$add = FindN "Add server"
if ($add) {
    $b = Bnd $add
    Say "  TAP card Add server @$($b['cx']),$($b['cy'])"
    TapXY $b['cx'] $b['cy']
} else { Say "  no Add server on card" }
Start-Sleep -Milliseconds 6000
$null = Cap "P2_after"
Say "--- state after answering password ---"
ListRows "P2"
DumpT "P2"
Say "DONE_P"
