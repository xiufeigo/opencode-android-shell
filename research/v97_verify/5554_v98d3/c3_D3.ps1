. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c3_entry.ps1"
$ErrorActionPreference = 'Stop'
InitDir
$null = FocusInfo

# close the failed-connection card with its OWN Cancel
if ($null -ne (FindN "Connection failed")) {
    $c = FindN "Cancel"
    if ($c) { $b = Bnd $c; Say "close Connection-failed card via its Cancel @ $($b['cx']),$($b['cy'])"; TapXY $b['cx'] $b['cy']; Start-Sleep -Milliseconds 1200 }
}
$null = Cap "D0_before"
Say "--- BEFORE rows ---"
ListRows "D0"

# the bottom-most 'Server actions' = the row we created (self-made rows are appended last)
$rows = New-Object System.Collections.ArrayList
foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Server actions") { $null = $rows.Add((Bnd $n)) } }
$rows = @($rows | Sort-Object { $_.t })
Say "row ⋮ count=$($rows.Count) y=$(($rows | ForEach-Object { $_.t }) -join ',')"
$target = $rows[-1]
Say "target row ⋮ bounds=[$($target['l']),$($target['t'])][$($target['r']),$($target['b'])]"

TapXY $target['cx'] $target['cy']
Start-Sleep -Milliseconds 1200
$null = Cap "D1_menu"
Say "--- overflow menu ---"
DumpT "D1_menu"

$del = FindN "Delete"
if ($del) {
    $b = Bnd $del
    Say "  TAP menu Delete @ $($b['cx']),$($b['cy']) bounds=[$($b['l']),$($b['t'])][$($b['r']),$($b['b'])]"
    TapXY $b['cx'] $b['cy']
} else { Say "  NO Delete in menu" }
Start-Sleep -Milliseconds 1500
$null = Cap "D2_bar"
Say "--- after Delete: confirm bar? ---"
$vp = Viewport
$title = FindN "Delete SSH server?"
if ($vp) { Say ("VIEWPORT = [{0},{1}][{2},{3}]  y in [{1},{3}]" -f $vp['l'],$vp['t'],$vp['r'],$vp['b']) }
if ($title) { Say "BAR_TITLE 'Delete SSH server?' bounds=$((Bnd $title).l),$((Bnd $title).t),$((Bnd $title).r),$((Bnd $title).b)" } else { Say "BAR_TITLE_NOT_IN_A11Y_TREE" }
DumpT "D2_bar"
Say "DONE_D2"
