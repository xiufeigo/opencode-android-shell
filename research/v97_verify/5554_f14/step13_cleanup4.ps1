# F14 step 13: the Delete dialog is stuck for an entry in 'Authentication required'.
# The dialog states "Its tunnel is disconnected first." -> disconnect the row, THEN delete.
. "$PSScriptRoot\f14lib.ps1"

Write-Host '=== dismiss the stuck confirm dialog (left button) ==='
$x = Dump 'CL5_00'
TapXY 556 1960
Start-Sleep -Seconds 2
$x = Dump 'CL5_01_afterDismiss'
$dlg = (Texts $x) | Where-Object { $_.Text -eq 'Delete SSH server?' }
Write-Host "dialog up after dismiss: $([bool]$dlg)"

Write-Host ''
Write-Host '=== disconnect the row ==='
$disc = (Texts $x) | Where-Object { $_.Text -eq 'Disconnect' } | Select-Object -First 1
if ($disc) {
    Write-Host "tapping 'Disconnect' @($($disc.CX),$($disc.CY))"
    TapXY $disc.CX $disc.CY
    Start-Sleep -Seconds 4
}
$x = Dump 'CL5_02_disconnected'
Write-Host 'panel after disconnect:'
(Txts := (Texts $x)) | Where-Object { $_.Y1 -gt 1100 -and $_.Y1 -lt 1800 } | ForEach-Object { Write-Host "  '$($_.Text)' @($($_.CX),$($_.CY))" }

Write-Host ''
Write-Host '=== now delete ==='
$row = (Texts $x) | Where-Object { $_.Text -like 'E2ECHK5554f14*' } | Select-Object -First 1
if ($row) {
    $b = (Texts $x) | Where-Object { $_.Text -eq 'Server actions' } | Select-Object -First 1
    Write-Host "row '$($row.Text)'; 'Server actions' @($($b.CX),$($b.CY))"
    TapXY $b.CX $b.CY
    Start-Sleep -Seconds 1
    $xm = Dump 'CL5_03_menu'
    $del = (Texts $xm) | Where-Object { $_.Text -like 'Delete*' } | Select-Object -First 1
    if ($del) {
        Write-Host "tapping 'Delete' @($($del.CX),$($del.CY))"
        TapXY $del.CX $del.CY
        Start-Sleep -Seconds 2
        $xc = Dump 'CL5_04_dialog'
        $dlg2 = (Texts $xc) | Where-Object { $_.Text -eq 'Delete SSH server?' }
        Write-Host "confirm dialog up: $([bool]$dlg2)"
        if ($dlg2) {
            Write-Host "tapping affirmative @759,1960"
            TapXY 759 1960
            Start-Sleep -Seconds 4
        }
    }
}

$x = Dump 'CL5_05_final'
$left = (Texts $x) | Where-Object { $_.Text -like 'E2ECHK5554f14*' }
$dlg3 = (Texts $x) | Where-Object { $_.Text -eq 'Delete SSH server?' }
Write-Host ''
Write-Host "f14 row present : $([bool]$left)"
Write-Host "dialog still up : $([bool]$dlg3)"
Write-Host "RESULT: $(if($left){'ENTRY NOT DELETED'}else{'CLEAN - all f14 entries removed'})"
Write-Host ''
Write-Host '=== final panel ==='
(Txts2 := (Texts $x)) | Where-Object { $_.Y1 -gt 1100 -and $_.Y1 -lt 1980 } | ForEach-Object { Write-Host "  '$($_.Text)' @($($_.CX),$($_.CY))" }
