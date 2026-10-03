# F14 step 2: fill KEY PATH with sentinel proof. Passphrase deliberately left EMPTY.
. "$PSScriptRoot\f14lib.ps1"

$KEYPATH = '/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'

$ok = $false
for ($t = 1; $t -le 3; $t++) {
    Write-Host ""
    Write-Host "=== KEY PATH try $t : sentinel 'KP1' ==="
    $xml = Dump "F14_kp_t$t"
    $f = FieldBelow $xml 'Key file path' 0
    if (-not $f) { Write-Host "  FAIL: no EditText below 'Key file path'"; ShowEdits $xml 'probe'; continue }
    Write-Host "  target bounds=$($f.Raw) centre=($($f.CX),$($f.CY)) pw=$($f.Password)"
    TapXY $f.CX $f.CY

    $x2 = Dump "F14_kp_t${t}_focus"
    $foc = (EditTexts $x2 | Where-Object { $_.Focused -eq 'true' })
    if (-not $foc) { Write-Host '  FAIL: nothing focused after tap'; ShowEdits $x2 'after tap'; continue }
    Write-Host "  focus OK -> bounds=$($foc[0].Raw)"

    TypeOne 'KP1'
    $x3 = Dump "F14_kp_t${t}_sent"
    $hit = (EditTexts $x3 | Where-Object { $_.Text -and $_.Text -like '*KP1*' })
    if (-not $hit) { Write-Host "  FAIL: sentinel KP1 not found"; ShowEdits $x3 'after sentinel'; continue }
    Write-Host "  SENTINEL READBACK OK -> bounds=$($hit[0].Raw) text='$($hit[0].Text)'"

    DelN 3
    TypeText $KEYPATH
    $x4 = Dump "F14_kp_t${t}_final"
    $fin = (EditTexts $x4 | Where-Object { $_.Text -and $_.Text -like '*id_ed25519*' })
    if ($fin) { Write-Host "  FINAL OK -> bounds=$($fin[0].Raw) text='$($fin[0].Text)'"; $ok = $true; break }
    Write-Host '  WARN: final keypath not on readback'
    ShowEdits $x4 'after final'
}
if (-not $ok) { Write-Host 'KEYPATH FAILED after 3 tries'; exit 1 }

CollapseIme
Write-Host ''
Write-Host '=== VERIFY FULL FORM (passphrase must stay EMPTY) ==='
$xf = Dump 'F14_form_complete'
ShowEdits $xf 'complete form'
Write-Host '--- labels ---'
foreach ($t in (Texts $xf)) { if ($t.Y1 -gt 1000 -and $t.Y1 -lt 1800) { Write-Host ("  '{0}' @({1},{2})" -f $t.Text, $t.CX, $t.CY) } }
