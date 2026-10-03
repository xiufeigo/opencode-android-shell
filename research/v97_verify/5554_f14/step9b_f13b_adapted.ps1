# F14 step 9b: F13b adapted.
# FINDING: in Password mode this build has NO stored-password field - the Add form is
# host / name / Authentication=Password only. Credentials are ALWAYS prompted at
# connect time, so the task's "build an entry with a wrong password" step is not
# expressible in 1.4.0. Adapted: submit a Password-mode entry, answer the prompt card
# with the real account password, and prove the keyboard-interactive path still works.
. "$PSScriptRoot\f14lib.ps1"

Write-Host '=== state check ==='
$x = Dump 'F13b2_00'
ShowEdits $x 'current'
foreach ($t in (Texts $x)) { if ($t.Y1 -gt 900 -and $t.Y1 -lt 1800) { Write-Host ("  '{0}' @({1},{2})" -f $t.Text, $t.CX, $t.CY) } }

# ---- submit if the form is still open ----
$add = (Texts $x) | Where-Object { $_.Text -eq 'Add server' } | Select-Object -First 1
if ($add) {
    Write-Host "tapping 'Add server' @($($add.CX),$($add.CY))"
    TapXY $add.CX $add.CY
    Start-Sleep -Seconds 4
}

# ---- host identity card -> trust ----
for ($i = 0; $i -lt 4; $i++) {
    $x = Dump "F13b2_01_card$i"
    $trust = (Texts $x) | Where-Object { $_.Text -eq 'Trust and connect' } | Select-Object -First 1
    if ($trust) {
        Write-Host "trusting host card @($($trust.CX),$($trust.CY))"
        TapXY $trust.CX $trust.CY
        Start-Sleep -Seconds 3
        continue
    }
    break
}

# ---- password card: answer with the REAL password ----
$x = Dump 'F13b2_02_pwcard'
Write-Host ''
Write-Host '=== after trust ==='
foreach ($t in (Texts $x)) { if ($t.Y1 -gt 900) { Write-Host ("  '{0}' [{1},{2}][{3},{4}]" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2) } }
ShowEdits $x 'pw card'

$field = (EditTexts $x) | Where-Object { $_.Password -eq 'true' } | Select-Object -First 1
if (-not $field) { Write-Host 'no masked field on the card - dumping and stopping'; exit 1 }
Write-Host "password field bounds=$($field.Raw) centre=($($field.CX),$($field.CY))"
TapXY $field.CX $field.CY
$x2 = Dump 'F13b2_03_focus'
if (-not ((EditTexts $x2) | Where-Object { $_.Focused -eq 'true' })) { Write-Host 'card field not focused'; ShowEdits $x2 'focus'; exit 1 }
Write-Host 'focus OK'

# first try a WRONG password to prove the card re-prompts (the intent of the original test)
TypeText 'wrongpw999'
CollapseIme
$x3 = Dump 'F13b2_04_wrong'
$cont = (Texts $x3) | Where-Object { $_.Text -in @('Continue', 'Connect', 'OK') } | Select-Object -First 1
if ($cont) { Write-Host "submitting WRONG password via '$($cont.Text)' @($($cont.CX),$($cont.CY))"; TapXY $cont.CX $cont.CY }
Start-Sleep -Seconds 4
$x4 = Dump 'F13b2_05_afterWrong'
Write-Host ''
Write-Host '=== after wrong password ==='
foreach ($t in (Texts $x4)) { if ($t.Y1 -gt 900) { Write-Host ("  '{0}' [{1},{2}][{3},{4}]" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2) } }
Write-Host '=== sshd after wrong ==='
SshdLog '-3min' | ForEach-Object { Write-Host "  $_" }

# now answer correctly if a card is up
$again = (EditTexts $x4) | Where-Object { $_.Password -eq 'true' } | Select-Object -First 1
if ($again) {
    Write-Host ''
    Write-Host '=== re-prompt detected -> answering with the CORRECT password ==='
    TapXY $again.CX $again.CY
    Start-Sleep -Milliseconds 600
    TypeText 'opencode'
    CollapseIme
    $x5 = Dump 'F13b2_06_typed'
    $cont2 = (Texts $x5) | Where-Object { $_.Text -in @('Continue', 'Connect', 'OK') } | Select-Object -First 1
    if ($cont2) { Write-Host "tapping '$($cont2.Text)' @($($cont2.CX),$($cont2.CY))"; TapXY $cont2.CX $cont2.CY }
    Start-Sleep -Seconds 5
    $x6 = Dump 'F13b2_07_final'
    Write-Host ''
    Write-Host '=== FINAL state ==='
    foreach ($t in (Texts $x6)) { if ($t.Y1 -gt 1000 -and $t.Y1 -lt 1800) { Write-Host ("  '{0}' [{1},{2}][{3},{4}]" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2) } }
    $conn = (Texts $x6) | Where-Object { $_.Text -eq 'Connected' }
    Write-Host ''
    Write-Host "F13b ASSERT Connected = $([bool]$conn)  (expect True)"
}
Write-Host ''
Write-Host '=== SSHD LOG (keyboard-interactive is EXPECTED here) ==='
SshdLog '-6min' | ForEach-Object { Write-Host "  $_" }
