# F14 step 10: cleanup - remove every entry created this round, clear faillock,
# verify /sdcard has no leftovers.
. "$PSScriptRoot\f14lib.ps1"

Write-Host '=== entries before cleanup ==='
$x = Dump 'CL_00_before'
foreach ($t in (Texts $x)) { if ($t.Text -like 'E2ECHK5554f14*' -or $t.Text -eq 'SSH tunnels' -or $t.Text -eq 'Connected' -or $t.Text -eq 'Disconnected' -or $t.Text -like 'Server not reachable*') { Write-Host ("  '{0}' [{1},{2}][{3},{4}] c=({5},{6})" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2, $t.CX, $t.CY) } }

# open the per-row 'Server actions' menu for each f14 row and look for Delete
for ($i = 0; $i -lt 6; $i++) {
    $x = Dump "CL_01_scan$i"
    $menus = (Texts $x) | Where-Object { $_.Text -eq 'Server actions' }
    if (-not $menus) { break }
    $target = $null
    foreach ($m in $menus) {
        # is there an f14 row close to this actions button?
        $near = (Texts $x) | Where-Object { $_.Text -like 'E2ECHK5554f14*' -and [Math]::Abs($_.CY - $m.CY) -lt 200 }
        if ($near) { $target = $m; break }
    }
    if (-not $target) { break }
    Write-Host "opening 'Server actions' for an f14 row @($($target.CX),$($target.CY))"
    TapXY $target.CX $target.CY
    Start-Sleep -Seconds 1
    $xm = Dump "CL_02_menu$i"
    $del = (Texts $xm) | Where-Object { $_.Text -like 'Delete*' } | Select-Object -First 1
    if ($del) {
        Write-Host "  'Delete' @($($del.CX),$($del.CY))"
        TapXY $del.CX $del.CY
        Start-Sleep -Seconds 1
        $xc = Dump "CL_03_confirm$i"
        $ok = (Texts $xc) | Where-Object { $_.Text -in @('Delete', 'Confirm', 'OK', 'Remove') } | Select-Object -Last 1
        if ($ok) { Write-Host "  confirm @($($ok.CX),$($ok.CY))"; TapXY $ok.CX $ok.CY; Start-Sleep -Seconds 1 }
    }
    else {
        Write-Host '  no Delete in menu; menu contents:'
        (Texts $xm) | Where-Object { $_.Y1 -gt 500 } | ForEach-Object { Write-Host "    '$_'" }
        # dismiss with TAB
        CollapseIme
        break
    }
}

Write-Host ''
Write-Host '=== entries after cleanup ==='
$x2 = Dump 'CL_04_after'
$left = (Texts $x2) | Where-Object { $_.Text -like 'E2ECHK5554f14*' }
if ($left) { $left | ForEach-Object { Write-Host "  STILL PRESENT: '$($_.Text)'" } }
else { Write-Host '  no E2ECHK5554f14* entries remain' }

Write-Host ''
Write-Host '=== faillock reset ==='
ResetFaillock
A shell 'run-as com.xiufeigo.opencodeshell ls -la files/ssh' | ForEach-Object { Write-Host "  $_" }

Write-Host ''
Write-Host '=== /sdcard residue check ==='
A shell 'ls /sdcard/_e2echk5554.png /sdcard/f14*.xml /sdcard/f14*.png /sdcard/m.xml /sdcard/s.xml /sdcard/sv.xml /sdcard/h.xml 2>/dev/null' | ForEach-Object { Write-Host "  $_" }
A shell 'rm -f /sdcard/_e2echk5554.png /sdcard/f14*.xml /sdcard/f14*.png /sdcard/m.xml /sdcard/s.xml /sdcard/sv.xml /sdcard/h.xml /sdcard/f1.xml /sdcard/f1.png' | Out-Null
A shell 'ls /sdcard/ | grep -Ei "e2echk|f14" ' | ForEach-Object { Write-Host "  LEFTOVER: $_" }
Write-Host '  (no LEFTOVER lines above = clean)'
