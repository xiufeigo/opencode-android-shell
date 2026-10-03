# F14 step 12: delete the last entry. Only ONE 'Server actions' button exists and the
# only f14 row is E2ECHK5554f14-b, so the button unambiguously belongs to it.
. "$PSScriptRoot\f14lib.ps1"

for ($i = 0; $i -lt 4; $i++) {
    $x = Dump "CL3_00_$i"
    $row = (Texts $x) | Where-Object { $_.Text -like 'E2ECHK5554f14*' } | Select-Object -First 1
    if (-not $row) { Write-Host 'CLEAN: no f14 rows remain'; break }
    $acts = (Texts $x) | Where-Object { $_.Text -eq 'Server actions' }
    Write-Host "row '$($row.Text)' @($($row.CX),$($row.CY)); actions buttons: $($acts.Count)"
    $b = $acts[0]
    Write-Host "tapping 'Server actions' @($($b.CX),$($b.CY))"
    TapXY $b.CX $b.CY
    Start-Sleep -Seconds 1
    $xm = Dump "CL3_01_menu_$i"
    Write-Host '  menu contents:'
    (Texts $xm) | Where-Object { $_.Y1 -gt 1000 } | ForEach-Object { Write-Host "    '$($_.Text)' @($($_.CX),$($_.CY))" }
    $del = (Texts $xm) | Where-Object { $_.Text -like 'Delete*' } | Select-Object -First 1
    if (-not $del) { Write-Host '  no Delete entry'; break }
    Write-Host "  tapping 'Delete' @($($del.CX),$($del.CY))"
    TapXY $del.CX $del.CY
    Start-Sleep -Seconds 1
    $xc = Dump "CL3_02_conf_$i"
    $ok = (Texts $xc) | Where-Object { $_.Text -in @('Delete', 'Confirm', 'OK', 'Remove') } | Select-Object -Last 1
    if ($ok) { Write-Host "  confirm '$($ok.Text)' @($($ok.CX),$($ok.CY))"; TapXY $ok.CX $ok.CY; Start-Sleep -Seconds 1 }
}

Write-Host ''
$x = Dump 'CL3_03_final'
Write-Host '=== final entry list ==='
$left = (Texts $x) | Where-Object { $_.Text -like 'E2ECHK5554f14*' }
if ($left) { $left | ForEach-Object { Write-Host "  STILL PRESENT: '$($_.Text)'" }; Write-Host 'RESULT: NOT CLEAN' }
else { Write-Host '  RESULT: CLEAN - no E2ECHK5554f14* entries remain' }
Write-Host '  (remaining SSH tunnel rows, for reference:)'
(Txts := (Texts $x)) | Where-Object { $_.Y1 -gt 1100 -and $_.Y1 -lt 1800 } | ForEach-Object { Write-Host "    '$($_.Text)'" }
