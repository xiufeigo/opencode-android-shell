# F14 step 6: answer the PASSPHRASE card -> F14b + F12a core assertion.
# Card coordinates are LEARNED here (not guessed) and re-verified by label every time:
#   passphrase EditText (pw=true)  @[138,1615][943,1741]  c=(540,1678)
#   'Continue'                     @[794,1779][938,1820]  c=(866,1800)
#   'Cancel'                       @[588,1779][699,1820]  c=(644,1800)
#   host card 'Trust and connect'  @[646,1812][938,1853]  c=(792,1832)
. "$PSScriptRoot\f14lib.ps1"

$PASS = 'keypass123'

# locate the masked field on the card
$xml = Dump 'F14_pp_probe'
$card = (EditTexts $xml) | Where-Object { $_.Password -eq 'true' } | Select-Object -First 1
if (-not $card) { Write-Host 'FAIL: no masked field on the passphrase card'; ShowEdits $xml 'probe'; exit 1 }
Write-Host "card EditText bounds=$($card.Raw) centre=($($card.CX),$($card.CY)) pw=$($card.Password)"

TapXY $card.CX $card.CY
$x2 = Dump 'F14_pp_focus'
$foc = (EditTexts $x2) | Where-Object { $_.Focused -eq 'true' }
if (-not $foc) { Write-Host 'FAIL: card field not focused'; ShowEdits $x2 'focus'; exit 1 }
Write-Host "focus OK -> $($foc[0].Raw)"

# sentinel proof on the card too
TypeOne 'PP1'
$x3 = Dump 'F14_pp_sent'
$hit = (EditTexts $x3) | Where-Object { $_.Text -and $_.Text -like '*PP1*' }
if ($hit) { Write-Host "card sentinel OK -> $($hit[0].Raw)" } else { Write-Host 'card sentinel NOT visible (masked field may hide text) - continuing' }
DelN 3

TypeText $PASS
$x4 = Dump 'F14_pp_typed'
Write-Host '=== card state before Continue ==='
foreach ($t in (Texts $x4)) { if ($t.Y1 -gt 1300) { Write-Host ("  '{0}' @({1},{2})" -f $t.Text, $t.CX, $t.CY) } }

$cont = (Texts $x4) | Where-Object { $_.Text -eq 'Continue' } | Select-Object -First 1
if (-not $cont) { Write-Host 'FAIL: no Continue button'; exit 1 }
Write-Host "tapping 'Continue' @($($cont.CX),$($cont.CY))"
$stamp = Get-Date -Format 'HH:mm:ss'
Write-Host "TAPPED AT $stamp"
TapXY $cont.CX $cont.CY

Start-Sleep -Seconds 5
$x5 = Dump 'F14_after_continue'
Write-Host ''
Write-Host '=== AFTER CONTINUE: texts y>1000 ==='
foreach ($t in (Texts $x5)) { if ($t.Y1 -gt 1000 -and $t.Y1 -lt 1800) { Write-Host ("  '{0}' [{1},{2}][{3},{4}]" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2) } }
Write-Host ''
Write-Host '=== SSHD LOG (the F14b verdict) ==='
SshdLog '-25min' | ForEach-Object { Write-Host "  $_" }
