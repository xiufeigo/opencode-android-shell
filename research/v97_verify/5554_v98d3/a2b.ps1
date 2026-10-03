. "$PSScriptRoot\c6.ps1" -Dev emulator-5554
$ErrorActionPreference = 'Stop'

# dir: -1 = finger moves UP  -> reveals LATER content (scroll down / toward form bottom)
#      +1 = finger moves DOWN-> reveals EARLIER content (scroll up / toward form top)
function ScrollBy([int]$dir) {
    $null = Uidump 'nav_tmp'
    $r = FormRect
    if(-not $r){ Write-Host '  ScrollBy SKIP (no form rect)'; return $false }
    $x = $r[2] - 20
    $mid = $r[1] + [int](($r[3]-$r[1]) * 0.5)
    $step = 120
    $y1 = $mid
    $y2 = $mid + ($dir * $step)
    $lo = $r[1] + 20; $hi = $r[3] - 20
    if($y1 -lt $lo){$y1=$lo}; if($y1 -gt $hi){$y1=$hi}
    if($y2 -lt $lo){$y2=$lo}; if($y2 -gt $hi){$y2=$hi}
    if($y1 -eq $y2){ Write-Host "  ScrollBy SKIP (at limit, rect $($r[1])..$($r[3]))"; return $false }
    Q shell "input swipe $x $y1 $x $y2 300" | Out-Null
    Start-Sleep -Milliseconds 650
    return $true
}
function Show($n,$tag){ Write-Host "  --- $tag IME=$(ImeShown) ---"; $n | ForEach-Object { Write-Host "    $_" }; return $n }
function FindLabel($n,[string]$p){ $m = $n | Select-String -Pattern ([regex]::Escape($p)) | Select-Object -First 1; if($m){ return $m.Line }; return $null }
function FieldUnder($n,[string]$label){
    $l = FindLabel $n $label; if(-not $l){ return $null }
    $lr = RectOf $l
    foreach($f in (Fields $n)){ $r = RectOf $f
        if($r[1] -ge $lr[1] -and $r[1] -le ($lr[1]+300) -and ($r[3]-$r[1]) -ge 50){ return $f } }
    return $null
}
function PwField($n){ Fields $n | Where-Object { $_ -match 'pw=true' -and (FieldH $_) -ge 50 } | Select-Object -First 1 }

Write-Host "=== A2b: sentinel create, corrected scroll direction ==="
Write-Host '--- GT before ---'
$GT0 = GtCount
$null = Uidump 'A30_start'
$n = NodesNow 'A30'
($n | Select-String -Pattern 'Host or SSH command|Server name|^Authentication\t|^Password\t|^Passphrase\t|^Private key\t|EditText\t|^Add server\t|^Cancel\t' | ForEach-Object { $_.Line }) | ForEach-Object { Write-Host "  S $_" }

# ---- 1. HOST: scroll toward TOP ----
Write-Host '--- step 1: Host (scroll toward form top) ---'
$hostFld = $null
for($i=0;$i -lt 12;$i++){
    $n = NodesNow 'nav_tmp'
    $l = FindLabel $n 'Host or SSH command'
    if($l){ $hostFld = FieldUnder $n 'Host or SSH command' }
    if($hostFld){ Write-Host "  host reachable after $i scroll-up(s)"; break }
    if(-not (ScrollBy 1)){ break }
}
if(-not $hostFld){
    $n = NodesNow 'A31_fail'
    Write-Host '  Host NOT found going up; trying down-sweep'
    for($i=0;$i -lt 12;$i++){ $n = NodesNow 'nav_tmp'; $hostFld = FieldUnder $n 'Host or SSH command'
        if($hostFld){ Write-Host "  host found after $i scroll-down(s)"; break }
        if(-not (ScrollBy -1)){ break } }
}
if(-not $hostFld){ ShotN 'A31_FAIL_host'; throw 'Host field unreachable' }
Write-Host "HOST_LABEL=$(FindLabel (NodesNow 'nav_tmp') 'Host or SSH command')"
Write-Host "HOST_FIELD=$hostFld"
if(-not (TapField $hostFld 'host')){ ShotN 'A31_FAIL_hostfocus'; throw 'host never focused' }
TypeText 'ssh -p 2222 sshtest@127.0.0.1'
Start-Sleep -Milliseconds 900
$n = NodesNow 'A32_typedHost'
Write-Host '--- readback ---'
$n | Select-String -Pattern 'EditText\t' | ForEach-Object { Write-Host "    $($_.Line)" }
CollapseIme

# ---- 2. NAME ----
Write-Host '--- step 2: Server name (scroll toward bottom) ---'
$nameFld = $null
for($i=0;$i -lt 12;$i++){
    $n = NodesNow 'nav_tmp'
    $nameFld = FieldUnder $n 'Server name (optional)'
    if($nameFld){ Write-Host "  name reachable after $i scroll-down(s)"; break }
    if(-not (ScrollBy -1)){ break }
}
if(-not $nameFld){ ShotN 'A33_FAIL_name'; throw 'name field unreachable' }
Write-Host "NAME_LABEL=$(FindLabel (NodesNow 'nav_tmp') 'Server name (optional)')"
Write-Host "NAME_FIELD=$nameFld"
if(-not (TapField $nameFld 'name')){ ShotN 'A33_FAIL_namefocus'; throw 'name never focused' }
TypeText 'E2ECHK5554d3c-1'
Start-Sleep -Milliseconds 900
$n = NodesNow 'A34_typedName'
Write-Host '--- readback ---'
$n | Select-String -Pattern 'EditText\t' | ForEach-Object { Write-Host "    $($_.Line)" }
CollapseIme

# ---- 3. PASSWORD (the v98 subject) ----
Write-Host '--- step 3: password field ---'
$pw = $null
for($i=0;$i -lt 12;$i++){
    $n = NodesNow 'nav_tmp'
    $pw = PwField $n
    if($pw){ Write-Host "  PASSWORD reachable after $i scroll-down(s)"; break }
    if(-not (ScrollBy -1)){ break }
}
if(-not $pw){ ShotN 'A35_FAIL_pw'; throw 'PASSWORD FIELD UNREACHABLE' }
Write-Host "PASSWORD_FIELD=$pw"
$pr = RectOf $pw; Write-Host "PASSWORD_BOUNDS=[$($pr[0]),$($pr[1])][$($pr[2]),$($pr[3])] h=$($pr[3]-$pr[1])"
$fr = FormRect
if($fr){ Write-Host "FORM_BODY_RECT=[$($fr[0]),$($fr[1])][$($fr[2]),$($fr[3])]  pw_inside=$($pr[1] -ge $fr[1] -and $pr[3] -le $fr[3])" }
if(-not (TapField $pw 'password')){ ShotN 'A35_FAIL_pwfocus'; throw 'password never focused' }
TypeText 'keypass123'
Start-Sleep -Milliseconds 900
$n = NodesNow 'A36_filled'
Write-Host '--- final form readback ---'
$n | Select-String -Pattern 'EditText\t|^Add server\t|^Cancel\t|^Password\t|^Passphrase\t' | ForEach-Object { Write-Host "    $($_.Line)" }
ShotN 'A36_filled'

# ---- 4. submit ----
Write-Host '--- step 4: assert Add server then tap ---'
$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if(-not $sub){ ShotN 'A37_FAIL_nosubmit'; throw 'Add server not visible' }
Write-Host "ADD_SERVER_NODE=$($sub.Line)"
Write-Host "ADD_SERVER_ENABLED_READOUT=see ancestors (a11y dump has no enabled attr; tapping is the test)"
$sp = CenterOf $sub.Line
GtRaw | Out-File (Join-Path $global:DIR 'gt_before_submit.xml.txt') -Encoding utf8
$GTB = GtCount
Write-Host "GT_BEFORE=$GTB ; TAP @ $($sp[0]),$($sp[1])"
Tap $sp[0] $sp[1] 1600
Start-Sleep -Seconds 2
$n = NodesNow 'A38_after'
ShotN 'A38_after_submit'
Write-Host '--- after submit: did the form close? did a row appear? ---'
$stillForm = [bool]($n | Select-String -Pattern 'Host or SSH command' -Quiet)
Write-Host "FORM_STILL_OPEN=$stillForm"
($n | Select-String -Pattern 'E2ECHK|sshtest|127.0.0.1|2222|Server actions|required|Please|Enter connection|Cancel|Add server' | ForEach-Object { $_.Line }) | ForEach-Object { Write-Host "    $_" }
GtRaw | Out-File (Join-Path $global:DIR 'gt_after_submit.xml.txt') -Encoding utf8
$GTA = GtCount
Write-Host "GT_AFTER=$GTA  DELTA=$($GTA-$GTB)"
$sa = @($n | Select-String -Pattern 'Server actions')
Write-Host "SERVER_ACTIONS_COUNT=$($sa.Count)"
Write-Host "DONE-A2B"
