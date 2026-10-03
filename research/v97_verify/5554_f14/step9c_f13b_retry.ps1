# F14 step 9c: F13b retry - clear the masked field properly, then type the real password.
# Lesson from 9b: the prompt field is NOT auto-cleared on re-prompt, so the wrong
# attempt stayed in the box and the 'correct' answer was appended (18 masked chars).
. "$PSScriptRoot\f14lib.ps1"

$x = Dump 'F13c_00'
Write-Host '=== current ==='
ShowEdits $x 'current'
foreach ($t in (Texts $x)) { if ($t.Y1 -gt 1200 -and $t.Y1 -lt 1800) { Write-Host ("  '{0}' @({1},{2})" -f $t.Text, $t.CX, $t.CY) } }

$field = (EditTexts $x) | Where-Object { $_.Password -eq 'true' } | Select-Object -First 1
if (-not $field) {
    Write-Host 'no masked field up. Trying Reconnect on the Password entry.'
    $rec = (Texts $x) | Where-Object { $_.Text -eq 'Reconnect' } | Select-Object -First 1
    if ($rec) { TapXY $rec.CX $rec.CY; Start-Sleep -Seconds 3 }
    $x = Dump 'F13c_01_retry'
    $field = (EditTexts $x) | Where-Object { $_.Password -eq 'true' } | Select-Object -First 1
}
if (-not $field) { Write-Host 'FAIL: still no masked field'; foreach ($t in (Texts $x)) { if ($t.Y1 -gt 900) { Write-Host "  '$($t.Text)'" } }; exit 1 }
Write-Host "password field bounds=$($field.Raw) centre=($($field.CX),$($field.CY))"
TapXY $field.CX $field.CY
Start-Sleep -Milliseconds 800
$x2 = Dump 'F13c_02_focus'
if (-not ((EditTexts $x2) | Where-Object { $_.Focused -eq 'true' })) { Write-Host 'FAIL: not focused'; exit 1 }

# MOVE_END then DEL x40 -> guaranteed empty
A shell 'input keyevent 123' | Out-Null
Start-Sleep -Milliseconds 200
DelN 40
$x3 = Dump 'F13c_03_cleared'
$m = (EditTexts $x3) | Where-Object { $_.Password -eq 'true' } | Select-Object -First 1
Write-Host "after clear: masked field text='$($m.Text)' (empty = good)"

TypeText 'opencode'
$x4 = Dump 'F13c_04_typed'
$m2 = (EditTexts $x4) | Where-Object { $_.Password -eq 'true' } | Select-Object -First 1
Write-Host "after typing: masked len=$($m2.Text.Length) (expect 8)"

$cont = (Texts $x4) | Where-Object { $_.Text -in @('Continue', 'Connect', 'OK', 'Add server') } | Select-Object -First 1
if (-not $cont) { Write-Host 'FAIL: no submit button'; foreach ($t in (Texts $x4)) { if ($t.Y1 -gt 1200) { Write-Host "  '$($t.Text)'" } }; exit 1 }
Write-Host "tapping '$($cont.Text)' @($($cont.CX),$($cont.CY))"
$stamp = Get-Date -Format 'HH:mm:ss'
Write-Host "TAPPED AT $stamp"
TapXY $cont.CX $cont.CY
Start-Sleep -Seconds 6

$x5 = Dump 'F13c_05_final'
Write-Host ''
Write-Host '=== FINAL ==='
foreach ($t in (Texts $x5)) { if ($t.Y1 -gt 1000 -and $t.Y1 -lt 1800) { Write-Host ("  '{0}' [{1},{2}][{3},{4}]" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2) } }
$conn = (Texts $x5) | Where-Object { $_.Text -eq 'Connected' }
Write-Host ''
Write-Host "F13b ASSERT Connected = $([bool]$conn)  (expect True)"
Write-Host '=== SSHD LOG ==='
SshdLog '-6min' | ForEach-Object { Write-Host "  $_" }
