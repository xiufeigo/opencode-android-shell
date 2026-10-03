# F14 step 14: delete the remaining f14 rows (-a, -c) now that -b is gone.
. "$PSScriptRoot\f14lib.ps1"

for ($i = 0; $i -lt 6; $i++) {
    $x = Dump "CL7_00_$i"
    $rows = (Texts $x) | Where-Object { $_.Text -like 'E2ECHK5554f14*' }
    if (-not $rows) { Write-Host 'CLEAN: no f14 rows remain'; break }
    Write-Host "--- iteration $i : rows = $(($rows | ForEach-Object { $_.Text }) -join ', ')"

    # dismiss any stuck dialog first
    $cd = (Texts $x) | Where-Object { $_.Text -eq 'Cancel' -and $_.CY -gt 1600 } | Select-Object -First 1
    if ($cd) { Write-Host "  dismissing dialog 'Cancel' @($($cd.CX),$($cd.CY))"; TapXY $cd.CX $cd.CY; Start-Sleep -Seconds 1; continue }

    $row = $rows[0]
    Write-Host "  target '$($row.Text)' @($($row.CX),$($row.CY))"
    $b = (Texts $x) | Where-Object { $_.Text -eq 'Server actions' -and $_.CY -gt $row.CY -and ($_.CY - $row.CY) -lt 260 } | Select-Object -First 1
    if (-not $b) { $b = (Texts $x) | Where-Object { $_.Text -eq 'Server actions' } | Select-Object -First 1 }
    if (-not $b) { Write-Host '  no Server actions button'; break }
    Write-Host "  'Server actions' @($($b.CX),$($b.CY))"
    TapXY $b.CX $b.CY
    Start-Sleep -Seconds 1

    $xm = Dump "CL7_01_menu_$i"
    $del = (Texts $xm) | Where-Object { $_.Text -like 'Delete*' } | Select-Object -First 1
    if (-not $del) { Write-Host '  no Delete in menu'; break }
    Write-Host "  'Delete' @($($del.CX),$($del.CY))"
    TapXY $del.CX $del.CY
    Start-Sleep -Seconds 2

    $xc = Dump "CL7_02_dlg_$i"
    $ok = (Texts $xc) | Where-Object { $_.Text -eq 'Delete' -and $_.CY -gt 1600 } | Select-Object -First 1
    if ($ok) { Write-Host "  confirm 'Delete' @($($ok.CX),$($ok.CY))"; TapXY $ok.CX $ok.CY; Start-Sleep -Seconds 3 }
    else { Write-Host '  (no confirm button found - row may have deleted directly)' }
}

Write-Host ''
$x = Dump 'CL7_03_final'
$left = (Texts $x) | Where-Object { $_.Text -like 'E2ECHK5554f14*' }
Write-Host "f14 rows left: $(($left | ForEach-Object { $_.Text }) -join ', ')"
Write-Host "RESULT: $(if($left){'NOT CLEAN'}else{'CLEAN - all f14 entries removed'})"
Write-Host '=== final SSH tunnels panel ==='
foreach ($t in (Texts $x)) { if ($t.Y1 -gt 1100 -and $t.Y1 -lt 1800) { Write-Host "  '$($t.Text)' @($($t.CX),$($t.CY))" } }
