# F14 step 5: accept the host-identity card, then learn/answer the PASSPHRASE card.
# Every control is re-located by label from a FRESH dump.
. "$PSScriptRoot\f14lib.ps1"

Write-Host '=== sshd log BEFORE trust ==='
SshdLog '-3min' | ForEach-Object { Write-Host "  $_" }

$xml = Dump 'F14_card_host'
$trust = (Texts $xml) | Where-Object { $_.Text -eq 'Trust and connect' } | Select-Object -First 1
if (-not $trust) { Write-Host 'FAIL: no Trust and connect'; exit 1 }
Write-Host "LEARNED CARD COORD (host card) -> 'Trust and connect' @($($trust.CX),$($trust.CY))"
Write-Host "  host card key 'Cancel' @($((Texts $xml | Where-Object { $_.Text -eq 'Cancel' } | Select-Object -First 1).CX),$((Texts $xml | Where-Object { $_.Text -eq 'Cancel' } | Select-Object -First 1).CY))"
TapXY $trust.CX $trust.CY
Start-Sleep -Seconds 3

$x2 = Dump 'F14_card_pass'
Write-Host ''
Write-Host '=== AFTER TRUST: all texts y>400 ==='
foreach ($t in (Texts $x2)) { if ($t.Y1 -gt 400) { Write-Host ("  '{0}' [{1},{2}][{3},{4}] c=({5},{6})" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2, $t.CX, $t.CY) } }
Write-Host '--- EditTexts on the passphrase card ---'
ShowEdits $x2 'passphrase card'
