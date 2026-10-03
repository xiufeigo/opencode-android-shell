. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Stop'

function ScrollBy([int]$dir) {
    $null = Uidump 'nav_tmp'
    $r = FormRect
    if(-not $r){ Write-Host '  ScrollBy SKIP (no form rect)'; return $false }
    $x = $r[2] - 20
    $mid = $r[1] + [int](($r[3]-$r[1]) * 0.5)
    $lo = $r[1] + 20; $hi = $r[3] - 20
    $y1 = $mid; $y2 = $mid + ($dir * 120)
    if($y1 -lt $lo){$y1=$lo}; if($y1 -gt $hi){$y1=$hi}
    if($y2 -lt $lo){$y2=$lo}; if($y2 -gt $hi){$y2=$hi}
    if($y1 -eq $y2){ Write-Host "  ScrollBy SKIP (at limit)"; return $false }
    Q shell "input swipe $x $y1 $x $y2 300" | Out-Null
    Start-Sleep -Milliseconds 650
    return $true
}
function PwField($n){ Fields $n | Where-Object { $_ -match 'pw=true' -and (FieldH $_) -ge 50 } | Select-Object -First 1 }

Write-Host '=== A2c: reach a TAPPABLE password field, fill, submit ==='
Write-Host '--- state carried over from A2b (host+name already typed) ---'
$null = Uidump 'A40_start'
$n = NodesNow 'A40'
$n | Select-String -Pattern 'EditText\t' | ForEach-Object { Write-Host "    $($_.Line)" }
$fr = FormRect
Write-Host "FORM_BODY_RECT=[$($fr[0]),$($fr[1])][$($fr[2]),$($fr[3])]"

Write-Host '--- keep scrolling DOWN until the pw field is FULLY inside the form body ---'
$ok = $false; $best = $null
for($i=0;$i -lt 16;$i++){
    $n = NodesNow 'nav_tmp'
    $pw = PwField $n
    if($pw){
        $r = RectOf $pw
        $inside = ($r[1] -ge $fr[1]) -and ($r[3] -le $fr[3])
        $cy = [int](($r[1]+$r[3])/2)
        $cyInside = ($cy -gt $fr[1]) -and ($cy -lt $fr[3])
        Write-Host "  try ${i}: pw=[$($r[0]),$($r[1])][$($r[2]),$($r[3])] cy=$cy fullyInside=$inside cyInside=$cyInside"
        if($inside -and $cyInside){ $best = $pw; $ok = $true; break }
    } else { Write-Host "  try ${i}: no pw field in tree" }
    if(-not (ScrollBy -1)){ Write-Host '  scroll hit limit'; break }
}
if(-not $ok){
    Write-Host '  FALLBACK: accepting partially-visible pw field and tapping its in-clip point'
    $n = NodesNow 'nav_tmp'; $pw = PwField $n
    if(-not $pw){ ShotN 'A41_FAIL_pw'; throw 'PASSWORD FIELD UNREACHABLE' }
    $r = RectOf $pw
    $cy = [int](($r[1]+$r[3])/2)
    $tapY = [Math]::Min($cy, $fr[3]-30)
    Write-Host "  tapping pw at y=$tapY (in-clip) instead of centre y=$cy"
    $null = Q shell "input tap 540 $tapY"
    Start-Sleep -Milliseconds 1000
} else {
    Write-Host "PASSWORD_FIELD=$best"
    $r = RectOf $best
    Write-Host "PASSWORD_BOUNDS=[$($r[0]),$($r[1])][$($r[2]),$($r[3])] h=$($r[3]-$r[1]) FULLY_INSIDE_FORM_BODY=YES"
    if(-not (TapField $best 'password')){
        Write-Host '  TapField failed; trying direct in-clip tap'
        $cy = [int](($r[1]+$r[3])/2)
        $null = Q shell "input tap 540 $cy"; Start-Sleep -Milliseconds 1000
    }
}
$n = NodesNow 'A42_pwfocus'
$foc = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'foc=true' } | Select-Object -First 1
Write-Host "FOCUSED_NODE=$($foc.Line)"
if($foc -and ($foc.Line -match 'pw=true')){
    Write-Host '>>> PASSWORD FIELD IS FOCUSED'
    TypeText 'keypass123'
    Start-Sleep -Milliseconds 1000
} else {
    Write-Host '>>> password NOT focused; typing anyway is unsafe - abort to stop'
    ShotN 'A42_FAIL_pwfocus'
    $n | Select-String -Pattern 'EditText\t' | ForEach-Object { Write-Host "    $($_.Line)" }
    throw 'password field could not be focused'
}
$n = NodesNow 'A43_filled'
Write-Host '--- final form readback ---'
$n | Select-String -Pattern 'EditText\t|^Add server\t|^Cancel\t' | ForEach-Object { Write-Host "    $($_.Line)" }
ShotN 'A43_filled'

Write-Host '--- submit ---'
$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if(-not $sub){ ShotN 'A44_FAIL_nosubmit'; throw 'Add server not visible' }
Write-Host "ADD_SERVER_NODE=$($sub.Line)"
$sp = CenterOf $sub.Line
$GTB = GtCount
GtRaw | Out-File (Join-Path $global:DIR 'gt_before_submit.xml.txt') -Encoding utf8
Write-Host "GT_BEFORE=$GTB ; TAP Add server @ $($sp[0]),$($sp[1])"
Tap $sp[0] $sp[1] 1600
Start-Sleep -Seconds 2
$n = NodesNow 'A44_after'
ShotN 'A44_after_submit'
$stillForm = [bool]($n | Select-String -Pattern 'Host or SSH command' -Quiet)
Write-Host "FORM_STILL_OPEN=$stillForm  (False => submit ACCEPTED, card dismissed)"
Write-Host '--- post-submit scan ---'
($n | Select-String -Pattern 'E2ECHK|sshtest|127.0.0.1|Server actions|required|Please|Enter connection|Cancel|Add server|Passphrase|Disconnected|Connected' | ForEach-Object { $_.Line }) | ForEach-Object { Write-Host "    $_" }
GtRaw | Out-File (Join-Path $global:DIR 'gt_after_submit.xml.txt') -Encoding utf8
$GTA = GtCount
Write-Host "GT_AFTER=$GTA  DELTA=$($GTA-$GTB)"
Write-Host "SERVER_ACTIONS_COUNT=$(@($n | Select-String -Pattern 'Server actions').Count)"
Write-Host "DONE-A2C"
