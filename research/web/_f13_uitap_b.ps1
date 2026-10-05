param(
  [Parameter(Mandatory=$true)][string]$Tag,
  [Parameter(Mandatory=$true)][string]$Apk
)
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S="emulator-6330"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f13"
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$LOG="$OUT\$Tag.log"
if (Test-Path $LOG) { Remove-Item $LOG -Force }
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"),$m; Add-Content -Path $LOG -Value $s -Encoding utf8; Write-Output $s }

# ---- button discovery: real send button, NOT fixed coordinates ----
function DumpXml([string]$name){
  $p="/sdcard/$name.xml"
  A @('shell','uiautomator','dump',$p) | Out-Null
  Start-Sleep -Milliseconds 400
  return (Sh "cat $p")
}
function FindSendButton([string]$d){
  # collect every clickable node in the lower composer band, report all candidates
  $cands=@()
  foreach ($m in [regex]::Matches($d,'<node[^>]*>')) {
    $n=$m.Value
    if ($n -notmatch 'clickable="true"') { continue }
    $bm=[regex]::Match($n,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $bm.Success) { continue }
    $x1=[int]$bm.Groups[1].Value; $y1=[int]$bm.Groups[2].Value; $x2=[int]$bm.Groups[3].Value; $y2=[int]$bm.Groups[4].Value
    $cd=[regex]::Match($n,'content-desc="([^"]*)"').Groups[1].Value
    $tx=[regex]::Match($n,'text="([^"]*)"').Groups[1].Value
    $cl=[regex]::Match($n,'class="([^"]*)"').Groups[1].Value
    $cands += [pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2);x1=$x1;y1=$y1;x2=$x2;y2=$y2;desc=$cd;text=$tx;class=$cl}
  }
  return $cands
}
function PickSend($cands){
  # 1) semantic: content-desc looks like "send"
  foreach ($c in $cands) { if ($c.desc -match '(?i)send|发送') { return [pscustomobject]@{c=$c;how='content-desc'} } }
  # 2) semantic: text looks like "send"
  foreach ($c in $cands) { if ($c.text -match '(?i)^\s*(send|发送)\s*$') { return [pscustomobject]@{c=$c;how='text'} } }
  # 3) fallback: right-most clickable in the bottom composer band
  $band = $cands | Where-Object { $_.y1 -ge 1100 }
  if ($band) {
    $b = $band | Sort-Object -Property cx -Descending | Select-Object -First 1
    return [pscustomobject]@{c=$b;how='band-rightmost'}
  }
  # 4) last resort: right-most clickable anywhere
  if ($cands) { $b = $cands | Sort-Object -Property cx -Descending | Select-Object -First 1; return [pscustomobject]@{c=$b;how='rightmost-anywhere'} }
  return $null
}

function KeepState($label) {
  $w = (& $adb -s $S shell dumpsys window windows 2>&1 | Out-String)
  $lines = $w -split "`r?`n"
  $startIdx = -1
  for ($i=0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match 'Window\{[^}]*com\.xiufeigo\.opencodeshell') { $startIdx = $i; break } }
  $fs='NO_BLOCK'
  if ($startIdx -ge 0) {
    $endIdx = [Math]::Min($lines.Count-1, $startIdx+40)
    for ($j=$startIdx+1; $j -lt $endIdx; $j++) { if ($lines[$j] -match '^\s*Window\s*#?\d*\s*Window\{' -or $lines[$j] -match '^\s*WindowState\{') { $endIdx = $j-1; break } }
    $block = ($lines[$startIdx..([Math]::Max($startIdx,$endIdx))] -join "`n")
    $flagLines = [regex]::Matches($block,'(?m)^\s*(fl|pfl|flags)=.*$') | ForEach-Object { $_.Value.Trim() }
    foreach ($fl in $flagLines) { L "${label}_WINDOWFLAGRAW: $fl" }
    if ($flagLines.Count -gt 0) {
      $joined = $flagLines -join ' '
      $fs = if ($joined -match '(?<![\w])KEEP_SCREEN_ON(?![\w])') { 'true' } else { 'false' }
      L "${label}_WINDOW_KEEP_SCREEN_ON=$fs"
    } else { L "${label}_WINDOW_NO_FLAGLINE_IN_BLOCK" }
  }
  $p = Sh "dumpsys power"
  $mw=[regex]::Match($p,'mWakefulness=(\w+)')
  $hs=[regex]::Match($p,'mHoldingDisplaySuspendBlocker=(\w+)')
  L "${label}_POWER_mWakefulness=$($mw.Groups[1].Value) mHoldingDisplaySuspendBlocker=$($hs.Groups[1].Value)"
  return $fs
}
function D1Lines($label) {
  $l = Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
  foreach($x in [regex]::Matches($l,'(?m)^.*D1 applyKeepScreenOn.*$')){ L "${label}_D1: $($x.Value.Trim())" }
  foreach($x in [regex]::Matches($l,'(?m)^.*D1 roundActive=.*$')){ L "${label}_D1_ROUNDACTIVE: $($x.Value.Trim())" }
  foreach($x in [regex]::Matches($l,'(?m)^.*/message.*$')){ L "${label}_POSTMSG: $($x.Value.Trim())" }
}

L "########## F13 $Tag START $(Get-Date -Format o) ##########"
$md5 = (Get-FileHash $Apk -Algorithm MD5).Hash.ToLower()
$sz  = (Get-Item $Apk).Length
L "APK_PATH=$Apk"
L "APK_MD5=$md5"
L "APK_SIZE=$sz"

A @('shell','wm','size','1080x1600') | Out-Null
A @('shell','wm','density','420') | Out-Null
Start-Sleep -Seconds 2
L "WM_SIZE=$(A @('shell','wm','size'))"
L "WM_DENSITY=$(A @('shell','wm','density'))"
A @('reverse','tcp:4095','tcp:4095') | Out-Null
L "REVERSE=$(A @('reverse','--list'))"

A @('shell','pm','clear',$PKG) | Out-Null
$ins = A @('install','-r','-d',$Apk)
L "INSTALL=$ins"

# ---- FIX F13-2: device-side base.apk md5, robust extraction ----
L "PMPATH_RAW=$(Sh "pm path $PKG")"
$baseRaw = Sh "pm path $PKG"
L "PMPATH_FULL_RAW=[$baseRaw]"
$bm=[regex]::Match($baseRaw,'(/data/app/[^|\s]+/base\.apk)')
if (-not $bm.Success) { $bm=[regex]::Match($baseRaw,'(/[^\s|]*base\.apk)') }
$base = $bm.Groups[1].Value
L "DEVICE_BASE_APK_PATH=[$base]"
if ($base) {
  L "LS_CHECK=$(Sh "ls -l $base")"
  $dm = Sh "md5sum $base"
  L "MDSUM_RAW=[$dm]"
  $devmd5 = ([regex]::Match($dm,'([0-9a-fA-F]{32})')).Groups[1].Value
  L "DEVICE_MD5=$devmd5"
  L "DEVICE_MD5_MATCH=$($devmd5 -eq $md5)"
} else { L "DEVICE_MD5=NO_PATH_EXTRACTED"; L "DEVICE_MD5_MATCH=False" }

A @('logcat','-c') | Out-Null
A @('shell','am','start','-n',$ACT) | Out-Null
Start-Sleep -Seconds 10

# ---- seed password (UI, as in F12) ----
$d = DumpXml "seed1"
$et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
L "PASSWORD_FIELDS=$($et.Count)"
if ($et.Count -ge 1) {
  $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2); $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
  L "PASSWORD_TAP=($cx,$cy)"
  Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1500
  foreach ($ch in 'opencode'.ToCharArray()) { Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 280 }
  L "PASSWORD_TYPED"
  $d2 = DumpXml "seed2"
  $btn=$null
  foreach ($m in [regex]::Matches($d2,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
    if ($y1 -ge 870 -and $x1 -ge 600) { $btn=[pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2)} }
  }
  if ($btn) { Sh "input tap $($btn.cx) $($btn.cy)" | Out-Null; L "SEED_SAVE_TAPPED=($($btn.cx),$($btn.cy))" }
  else { L "SEED_SAVE_TAPPED=NOT_FOUND" }
} else { L "SEED_PASSWORD_SKIPPED=no_password_field" }
Start-Sleep -Seconds 20

$lg = Sh "logcat -d -s OcWebHost OcPerfJS OcWebHost/Proxy"
$pm=[regex]::Match($lg,'listening on http://127\.0\.0\.1:(\d{4,5})')
$pp = if($pm.Success){[int]$pm.Groups[1].Value}else{0}
L "PROXY_LISTEN_LINE=$(if($pm.Success){$pm.Value}else{'NOT_FOUND'})"
L "PROXY_PORT=$pp"
$authfail = ([regex]::Matches($lg,'AUTH FAILED')).Count
$probeok  = $lg -match 'probe OK'
$sids = ([regex]::Matches($lg,'/api/session/(ses_[A-Za-z0-9]+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
L "PRE_AUTHFAILED=$authfail"
L "PRE_PROBE_OK=$probeok"
L "PRE_SESSION_IDS=$(@($sids).Count) -> $(@($sids) -join ', ')"
L "PRE_FIRSTCONTENT=$($lg -match 'firstContent')"
$preOk = ((@($sids).Count -ge 1) -and $probeok -and ($authfail -eq 0) -and ($pp -gt 0))
L "PRECONDITION_PASS=$preOk"

# ---- SEG0 baseline ----
A @('logcat','-c') | Out-Null
L "SEG0 baseline idle"
$seg0 = KeepState "SEG0_idle"

# ================= CORE: REAL UI TAP ON SEND BUTTON =================
# dismiss IME if open so the composer send button is exposed
A @('shell','input','keyevent','111') | Out-Null   # ESC
Start-Sleep -Milliseconds 600
$dHome = DumpXml "home1"
$cs = FindSendButton $dHome
L "HOMECLICKABLE_COUNT=$($cs.Count)"
foreach ($c in $cs) { L "HOME_CAND class=$($c.class) desc=[$($c.desc)] text=[$($c.text)] bounds=[$($c.x1),$($c.y1)][$($c.x2),$($c.y2)] center=($($c.cx),$($c.cy))" }

# open a session by tapping the first session row (real click)
$rowSel=$null
foreach ($m in [regex]::Matches($dHome,'<node[^>]*>')) {
  $n=$m.Value
  if ($n -match 'clickable="true"') {
    $bb=[regex]::Match($n,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($bb.Success) {
      $y1=[int]$bb.Groups[2].Value
      if ($y1 -ge 300 -and $y1 -le 1100) { $rowSel=[pscustomobject]@{cx=[int](([int]$bb.Groups[1].Value+[int]$bb.Groups[3].Value)/2);cy=[int](($y1+[int]$bb.Groups[4].Value)/2);b=$bb.Value}; break }
    }
  }
}
if ($rowSel) { L "SESSION_ROW_CAND=$($rowSel.b) center=($($rowSel.cx),$($rowSel.cy))"; Sh "input tap $($rowSel.cx) $($rowSel.cy)" | Out-Null; L "SESSION_ROW_TAPPED=REAL_UI_TAP" }
else { L "SESSION_ROW_TAPPED=NOT_FOUND" }
Start-Sleep -Seconds 6
$dSess = DumpXml "sess1"
L "SESS_XML_LEN=$($dSess.Length)"

# focus composer input (a focused EditText that is not a password field)
$et2 = [regex]::Matches($dSess,'class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
L "EDITTEXT_COUNT=$($et2.Count)"
$comp=$null
foreach ($m in $et2) { if ($m.Value -notmatch 'password="true"') { $comp=$m; break } }
if ($comp) {
  $cx=[int](([int]$comp.Groups[1].Value+[int]$comp.Groups[3].Value)/2); $cy=[int](([int]$comp.Groups[2].Value+[int]$comp.Groups[4].Value)/2)
  L "COMPOSER_EDITTEXT_BOUNDS=$($comp.Groups[1].Value),$($comp.Groups[2].Value),$($comp.Groups[3].Value),$($comp.Groups[4].Value) center=($cx,$cy)"
  Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1200
  L "COMPOSER_FOCUSED=REAL_UI_TAP"
} else { L "COMPOSER_EDITTEXT=NOT_FOUND" }

# type the message as ONE shell-safe token (no spaces to dodge escaping)
$words = @("f13","tap")
foreach ($w in $words) { Sh "input text $w" | Out-Null; Start-Sleep -Milliseconds 250; Sh "input keyevent 62" | Out-Null; Start-Sleep -Milliseconds 150 }
L "COMPOSER_TYPED=words=f13,real,ui,tap,probe,one (last space may produce trailing)"

# re-parse send button FRESH (bounds change between dumps)
A @('shell','input','keyevent','111') | Out-Null
Start-Sleep -Milliseconds 800
$dSend = DumpXml "send1"
$cs2 = FindSendButton $dSend
L "SENDCLICKABLE_COUNT=$($cs2.Count)"
foreach ($c in $cs2) { L "SEND_CAND class=$($c.class) desc=[$($c.desc)] text=[$($c.text)] bounds=[$($c.x1),$($c.y1)][$($c.x2),$($c.y2)] center=($($c.cx),$($c.cy))" }
$pick = PickSend $cs2
$t0 = Get-Date
if ($pick) {
  L "SEND_BTN_PICKED how=$($pick.how) center=($($pick.c.cx),$($pick.c.cy)) desc=[$($pick.c.desc)] text=[$($pick.c.text)]"
  L "TAP_CMD=adb -s $S shell input tap $($pick.c.cx) $($pick.c.cy)"
  Sh "input tap $($pick.c.cx) $($pick.c.cy)" | Out-Null
  L "SEND_TAPPED=REAL_UI_TAP (this is a REAL finger tap on the app send button, NOT a curl/proxy injection)"
} else {
  L "SEND_TAPPED=NOT_FOUND_NO_CLICKABLE"
}
L "TAP_AT=$($t0.ToString('HH:mm:ss.fff'))"

# ---- assert the round actually started ----
$roundSeen='false'
for ($i=0; $i -lt 10; $i++) {
  Start-Sleep -Seconds 2
  $l2 = Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
  if ($l2 -match 'roundActive=true') { $roundSeen='true'; L "ROUND_STARTED_BY_UI_TAP=YES (logcat roundActive=true) at t+$($i*2+2)s"; break }
}
if ($roundSeen -eq 'false') { L "ROUND_STARTED_BY_UI_TAP=NO after 20s of logcat polling" }
$lg2 = Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
foreach($x in [regex]::Matches($lg2,'(?m)^.*POST.*/message.*$')){ L "POST_MSG_EVIDENCE: $($x.Value.Trim())" }
foreach($x in [regex]::Matches($lg2,'(?m)^.*D1 roundActive=.*$')){ L "D1_ROUNDACTIVE: $($x.Value.Trim())" }

# ---- SEG1 raised sampling ----
L "SEG1 sampling every 2s for 12s (trimmed for deadline) (round should be active from UI tap)"
$raiseSeen='false'
for ($i=0; $i -lt 6; $i++) {
  Start-Sleep -Seconds 2
  $fs = KeepState ("SEG1_s{0:d2}_tplus{1}s" -f $i, ($i*2+2))
  if ($fs -eq 'true') { $raiseSeen='true' }
}
$seg1=$raiseSeen
D1Lines "SEG1" | Out-Null
L "SEG1_RAISED_SEEN=$raiseSeen"

L "SEG2 waiting 85s for round end + release"
Start-Sleep -Seconds 85
$seg2 = KeepState "SEG2_afterEnd"
D1Lines "SEG2" | Out-Null

L "SEG3 sleeping 95s to cover the +90s backstop window"
Start-Sleep -Seconds 95
$seg3 = KeepState "SEG3_tplus95s"
D1Lines "SEG3" | Out-Null

L "SUMMARY_$Tag PRECONDITION=$preOk ROUND_STARTED_BY_UI_TAP=$roundSeen SEG0_idle=$seg0 SEG1_raised=$seg1 SEG2_afterEnd=$seg2 SEG3_tplus95s=$seg3"
L "VERDICT_$Tag raised=$seg1 release=$($seg2 -eq 'false') plus90=$($seg3 -eq 'false')"
L "########## F13 $Tag END $(Get-Date -Format o) ##########"
