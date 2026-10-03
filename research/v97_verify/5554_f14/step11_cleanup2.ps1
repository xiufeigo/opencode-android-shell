# F14 step 11: finish cleanup - delete the surviving entry and purge /sdcard dumps.
# The first pass used a lowercase glob (/sdcard/f14*) which does not match the
# uppercase F14_*/F12b_*/F13*/CL_* dumps; Android's shell glob is case-sensitive.
. "$PSScriptRoot\f14lib.ps1"

Write-Host '=== A. delete remaining E2ECHK5554f14 rows ==='
for ($i = 0; $i -lt 5; $i++) {
    $x = Dump "CL2_00_scan$i"
    $row = (Texts $x) | Where-Object { $_.Text -like 'E2ECHK5554f14*' } | Select-Object -First 1
    if (-not $row) { Write-Host '  no f14 rows remain'; break }
    Write-Host "  row '$($row.Text)' @($($row.CX),$($row.CY))"
    $menus = (Texts $x) | Where-Object { $_.Text -eq 'Server actions' }
    $near = $menus | Where-Object { [Math]::Abs($_.CY - $row.CY) -lt 200 } | Select-Object -First 1
    if (-not $near) {
        Write-Host '  no Server actions button aligned with that row; current rows/actions:'
        (Texts $x) | Where-Object { $_.Text -eq 'Server actions' -or $_.Text -like 'E2ECHK5554f14*' } | ForEach-Object { Write-Host "    '$($_.Text)' @($($_.CX),$($_.CY))" }
        break
    }
    Write-Host "  opening 'Server actions' @($($near.CX),$($near.CY))"
    TapXY $near.CX $near.CY
    Start-Sleep -Seconds 1
    $xm = Dump "CL2_01_menu$i"
    $del = (Texts $xm) | Where-Object { $_.Text -like 'Delete*' } | Select-Object -First 1
    if ($del) {
        Write-Host "  'Delete' @($($del.CX),$($del.CY))"
        TapXY $del.CX $del.CY
        Start-Sleep -Seconds 1
        $xc = Dump "CL2_02_confirm$i"
        $ok = (Texts $xc) | Where-Object { $_.Text -in @('Delete', 'Confirm', 'OK', 'Remove') } | Select-Object -Last 1
        if ($ok) { Write-Host "  confirm '$($ok.Text)' @($($ok.CX),$($ok.CY))"; TapXY $ok.CX $ok.CY; Start-Sleep -Seconds 1 }
    }
    else { Write-Host '  no Delete in menu'; break }
}

Write-Host ''
Write-Host '=== B. entries after ==='
$x = Dump 'CL2_03_after'
$left = (Texts $x) | Where-Object { $_.Text -like 'E2ECHK5554f14*' }
if ($left) { $left | ForEach-Object { Write-Host "  STILL PRESENT: '$($_.Text)' @($($_.CX),$($_.CY))" } }
else { Write-Host '  CLEAN: no E2ECHK5554f14* entries remain' }

Write-Host ''
Write-Host '=== C. purge /sdcard dumps (case-correct globs) ==='
A shell "rm -f /sdcard/F14_*.xml /sdcard/F14_*.png /sdcard/F14a_*.xml /sdcard/F14a_*.png /sdcard/F12b_*.xml /sdcard/F12b_*.png /sdcard/F13b_*.xml /sdcard/F13b_*.png /sdcard/F13b2_*.xml /sdcard/F13b2_*.png /sdcard/F13c_*.xml /sdcard/F13c_*.png /sdcard/CL_*.xml /sdcard/CL_*.png /sdcard/CL2_*.xml /sdcard/CL2_*.png /sdcard/f14a.xml /sdcard/f14a_state.png /sdcard/h.xml /sdcard/m.xml /sdcard/s.xml /sdcard/s2.xml /sdcard/sv.xml /sdcard/f1.xml /sdcard/f1.png /sdcard/_e2echk5554.png" | Out-Null
$rem = A shell "ls /sdcard/ | grep -Ei 'e2echk|f14|f12b|f13b|f13c|CL2?'"
if ($rem) { $rem | ForEach-Object { Write-Host "  LEFTOVER: $_" } }
else { Write-Host '  CLEAN: no f14/e2echk residue on /sdcard' }
Write-Host "  (note: $(A shell "ls /sdcard/ | grep -c .")  total files remain on /sdcard - unrelated to this round)"

Write-Host ''
Write-Host '=== D. faillock final ==='
A shell 'run-as com.xiufeigo.opencodeshell ls -la files/ssh' | ForEach-Object { Write-Host "  $_" }
