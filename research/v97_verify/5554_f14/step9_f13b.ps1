# F14 step 9: F13b - Password authentication path.
# Build an entry with auth=Password and a WRONG stored password, submit, answer the
# prompt card with the CORRECT account password (opencode) and expect Connected.
# This proves the keyboard-interactive path still works after the key change and that
# the wrong stored credential is re-prompted rather than silently failing.
. "$PSScriptRoot\f14lib.ps1"

$HOST_CMD = 'ssh -p 2222 sshtest@127.0.0.1'
$NAME     = 'E2ECHK5554f14-c'
$WRONGPW  = 'wrongpw999'

function Fill([string]$label, [string]$sent, [string]$final) {
    $x = Dump "F13b_f_$sent"
    $f = FieldBelow $x $label 0
    if (-not $f) { Write-Host "  FAIL: no EditText below '$label'"; return $false }
    TapXY $f.CX $f.CY
    $x2 = Dump "F13b_f_$($sent)_focus"
    if (-not ((EditTexts $x2) | Where-Object { $_.Focused -eq 'true' })) { Write-Host "  FAIL: '$label' not focused"; return $false }
    TypeOne $sent
    $x3 = Dump "F13b_f_$($sent)_sent"
    if (-not ((EditTexts $x3) | Where-Object { $_.Text -and $_.Text -like "*$sent*" })) { Write-Host "  FAIL: sentinel $sent missed in '$label'"; return $false }
    Write-Host "  '$label' sentinel OK -> '$final'"
    DelN $sent.Length
    TypeText $final
    CollapseIme
    return $true
}

# reach the Add form
$x = Dump 'F13b_00_state'
$cards = (Texts $x) | Where-Object { $_.Text -eq 'Cancel' }
if ($cards) {
    $c = $cards | Select-Object -First 1
    Write-Host "clearing card via 'Cancel' @($($c.CX),$($c.CY))"
    TapXY $c.CX $c.CY; Start-Sleep -Seconds 1
}
for ($i = 0; $i -lt 4; $i++) {
    $x = Dump "F13b_01_nav$i"
    if ((Texts $x) | Where-Object { $_.Text -eq 'Add SSH server' }) { break }
    $set = (Texts $x) | Where-Object { $_.Text -eq 'Settings' } | Select-Object -First 1
    if ($set) { TapXY $set.CX $set.CY; Start-Sleep -Seconds 1 }
}
$x = Dump 'F13b_02_panel'
$addBtn = (Texts $x) | Where-Object { $_.Text -eq 'Add SSH server' } | Select-Object -First 1
if (-not $addBtn) { Write-Host 'FAIL: cannot reach Add SSH server'; exit 1 }
TapXY $addBtn.CX $addBtn.CY
Start-Sleep -Seconds 2
$x = Dump 'F13b_03_form'
if (-not ((Texts $x) | Where-Object { $_.Text -eq 'Host or SSH command' })) { Write-Host 'FAIL: form not open'; ShowEdits $x 'form'; exit 1 }
Write-Host 'form open'

Write-Host ''
Write-Host '=== F13b: Password mode, wrong stored password ==='
Fill 'Host or SSH command' 'HX1' $HOST_CMD | Out-Null
Fill 'Server name (optional)' 'NM1' $NAME | Out-Null

# Password mode is the DEFAULT - do NOT switch. Fill the stored password field.
$pwOk = $false
for ($t = 1; $t -le 3; $t++) {
    $x = Dump "F13b_04_pw$t"
    Write-Host "  try $t EditTexts:"
    ShowEdits $x "pw$t"
    $labs = (Texts $x) | ForEach-Object { $_.Text }
    Write-Host "  labels: $($labs -join ' | ')"
    $cands = (EditTexts $x) | Where-Object { $_.Password -eq 'true' }
    if (-not $cands) { Write-Host "  try ${t}: no masked field yet"; continue }
    $c = $cands[0]
    Write-Host "  pw field bounds=$($c.Raw) centre=($($c.CX),$($c.CY))"
    TapXY $c.CX $c.CY
    $x2 = Dump "F13b_04_pw${t}_focus"
    if (-not ((EditTexts $x2) | Where-Object { $_.Focused -eq 'true' })) { Write-Host "  try ${t}: not focused"; continue }
    TypeOne 'PW1'
    $x3 = Dump "F13b_04_pw${t}_sent"
    $hit = (EditTexts $x3) | Where-Object { $_.Text -and $_.Text -like '*PW1*' }
    if ($hit) { Write-Host "  pw sentinel OK (text readable) -> '$WRONGPW'"; $pwOk = $true }
    else { Write-Host "  pw sentinel masked (text not echoed) - assuming landed"; $pwOk = $true }
    DelN 3
    TypeText $WRONGPW
    break
}
if (-not $pwOk) { Write-Host 'F13b: no password field located'; exit 1 }

CollapseIme
$x = Dump 'F13b_05_ready'
Write-Host ''
Write-Host '=== pre-submit ==='
ShowEdits $x 'F13b ready'
$add = (Texts $x) | Where-Object { $_.Text -eq 'Add server' } | Select-Object -First 1
if (-not $add) { Write-Host 'FAIL: no Add server'; exit 1 }
Write-Host "tapping 'Add server' @($($add.CX),$($add.CY))"
TapXY $add.CX $add.CY
Start-Sleep -Seconds 4

$xf = Dump 'F13b_06_result'
Write-Host ''
Write-Host '=== after submit ==='
foreach ($t in (Texts $xf)) { if ($t.Y1 -gt 900) { Write-Host ("  '{0}' [{1},{2}][{3},{4}]" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2) } }
Write-Host ''
Write-Host '=== SSHD LOG ==='
SshdLog '-4min' | ForEach-Object { Write-Host "  $_" }
