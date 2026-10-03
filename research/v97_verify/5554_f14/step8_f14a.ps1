# F14 step 8: F14a - private key path the app CANNOT read.
# Expected: panel shows "Can't read the private key file. Check the path and read permission."
. "$PSScriptRoot\f14lib.ps1"

$HOST_CMD = 'ssh -p 2222 sshtest@127.0.0.1'
$NAME     = 'E2ECHK5554f14-a'
$BADKEY   = '/sdcard/Download/nope_id_ed25519'

# ---- 0. clear any open card, make sure we are on the Servers panel ----
$x = Dump 'F14a_00_state'
$cards = (Texts $x) | Where-Object { $_.Text -eq 'Cancel' -or $_.Text -eq 'Trust and connect' }
if ($cards) {
    $c = (Texts $x) | Where-Object { $_.Text -eq 'Cancel' } | Select-Object -First 1
    if ($c) { Write-Host "clearing open card via 'Cancel' @($($c.CX),$($c.CY))"; TapXY $c.CX $c.CY; Start-Sleep -Seconds 1 }
}
# back to Servers panel if needed
for ($i = 0; $i -lt 4; $i++) {
    $x = Dump "F14a_01_nav$i"
    if ((Texts $x) | Where-Object { $_.Text -eq 'Add SSH server' }) { break }
    $set = (Texts $x) | Where-Object { $_.Text -eq 'Settings' } | Select-Object -First 1
    if ($set) { TapXY $set.CX $set.CY; Start-Sleep -Seconds 1 }
}
$x = Dump 'F14a_02_panel'
$addBtn = (Texts $x) | Where-Object { $_.Text -eq 'Add SSH server' } | Select-Object -First 1
if (-not $addBtn) { Write-Host 'FAIL: cannot reach Add SSH server'; exit 1 }
TapXY $addBtn.CX $addBtn.CY
Start-Sleep -Seconds 2
$x = Dump 'F14a_03_form'
ShowEdits $x 'form open'
if (-not ((Texts $x) | Where-Object { $_.Text -eq 'Host or SSH command' })) { Write-Host 'FAIL: form not open'; exit 1 }

function Fill([string]$label, [string]$sent, [string]$final) {
    $x = Dump "F14a_f_$sent"
    $f = FieldBelow $x $label 0
    if (-not $f) { Write-Host "  FAIL: no EditText below '$label'"; return $false }
    TapXY $f.CX $f.CY
    $x2 = Dump "F14a_f_$($sent)_focus"
    if (-not ((EditTexts $x2) | Where-Object { $_.Focused -eq 'true' })) { Write-Host "  FAIL: '$label' not focused"; return $false }
    TypeOne $sent
    $x3 = Dump "F14a_f_$($sent)_sent"
    if (-not ((EditTexts $x3) | Where-Object { $_.Text -and $_.Text -like "*$sent*" })) { Write-Host "  FAIL: sentinel $sent not landed in '$label'"; return $false }
    Write-Host "  '$label' sentinel OK -> '$final'"
    DelN $sent.Length
    TypeText $final
    CollapseIme
    return $true
}

Write-Host ''
Write-Host '=== filling F14a entry ==='
Fill 'Host or SSH command' 'HX1' $HOST_CMD | Out-Null
Fill 'Server name (optional)' 'NM1' $NAME | Out-Null

# auth -> Private key
$x = Dump 'F14a_04_auth'
$sel = (Texts $x) | Where-Object { $_.Text -eq 'Password' } | Select-Object -First 1
if ($sel) {
    Write-Host "  auth selector 'Password' @($($sel.CX),$($sel.CY))"
    TapXY $sel.CX $sel.CY
    Start-Sleep -Seconds 1
    $x2 = Dump 'F14a_05_opts'
    $pk = (Texts $x2) | Where-Object { $_.Text -eq 'Private key' } | Select-Object -Last 1
    if ($pk) { Write-Host "  'Private key' @($($pk.CX),$($pk.CY))"; TapXY $pk.CX $pk.CY; Start-Sleep -Seconds 1 }
}

# bad key path
$badOk = $false
for ($t = 1; $t -le 3; $t++) {
    $x = Dump "F14a_06_kp$t"
    $f = FieldBelow $x 'Key file path' 0
    if (-not $f) { Write-Host "  try ${t}: no keypath field"; continue }
    Write-Host "  keypath bounds=$($f.Raw) centre=($($f.CX),$($f.CY))"
    TapXY $f.CX $f.CY
    $x2 = Dump "F14a_06_kp${t}_focus"
    if (-not ((EditTexts $x2) | Where-Object { $_.Focused -eq 'true' })) { Write-Host "  try ${t}: not focused"; continue }
    TypeOne 'KP1'
    $x3 = Dump "F14a_06_kp${t}_sent"
    if (-not ((EditTexts $x3) | Where-Object { $_.Text -and $_.Text -like '*KP1*' })) { Write-Host "  try ${t}: sentinel missed"; continue }
    Write-Host "  keypath sentinel OK"
    DelN 3
    TypeText $BADKEY
    $badOk = $true
    break
}
if (-not $badOk) { Write-Host 'F14a: could not fill bad keypath'; exit 1 }

CollapseIme
$x = Dump 'F14a_07_ready'
Write-Host ''
Write-Host '=== pre-submit form ==='
ShowEdits $x 'F14a ready'
$add = (Texts $x) | Where-Object { $_.Text -eq 'Add server' } | Select-Object -First 1
if (-not $add) { Write-Host 'FAIL: no Add server'; exit 1 }
Write-Host "tapping 'Add server' @($($add.CX),$($add.CY))"
TapXY $add.CX $add.CY
Start-Sleep -Seconds 4

$xf = Dump 'F14a_08_result'
Write-Host ''
Write-Host '=== F14a RESULT texts y>900 ==='
$hit = $false
foreach ($t in (Texts $xf)) {
    if ($t.Y1 -gt 900) {
        Write-Host ("  '{0}' [{1},{2}][{3},{4}]" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2)
        if ($t.Text -like '*private key*' -or $t.Text -like "*Can't read*") { $hit = $true }
    }
}
Write-Host ''
Write-Host "F14a ASSERT (Can't read the private key file...) = $hit  (expect True)"
SshdLog '-3min' | ForEach-Object { Write-Host "  sshd: $_" }
