param(
  [Parameter(Mandatory=$true)][string]$Tag,
  [Parameter(Mandatory=$true)][string]$Apk,
  [int]$SegSamples = 10
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
function DumpXml($name){ $p="/sdcard/$name.xml"; A @('shell','uiautomator','dump',$p) | Out-Null; Start-Sleep -Milliseconds 350; return (Sh "cat $p") }
function Nodes($d){
  $o=@()
  foreach ($m in [regex]::Matches($d,'<node[^>]*>')) {
    $n=$m.Value
    $bm=[regex]::Match($n,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'); if(-not $bm.Success){continue}
    $x1=[int]$bm.Groups[1].Value;$y1=[int]$bm.Groups[2].Value;$x2=[int]$bm.Groups[3].Value;$y2=[int]$bm.Groups[4].Value
    if ($x1 -eq 0 -and $y1 -eq 0 -and $x2 -eq 0) { continue }
    $o += [pscustomobject]@{
      click=([regex]::Match($n,'clickable="([^"]*)"').Groups[1].Value)
      cls=([regex]::Match($n,'class="([^"]*)"').Groups[1].Value)
      desc=([regex]::Match($n,'content-desc="([^"]*)"').Groups[1].Value)
      text=([regex]::Match($n,'text="([^"]*)"').Groups[1].Value)
      x1=$x1;y1=$y1;x2=$x2;y2=$y2;cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2) }
  }
  return $o
}
function KeepState($label) {
  $w = (& $adb -s $S shell dumpsys window windows 2>&1 | Out-String)
  $lines = $w -split "`r?`n"; $startIdx=-1
  for ($i=0;$i -lt $lines.Count;$i++){ if($lines[$i] -match 'Window\{[^}]*com\.xiufeigo\.opencodeshell'){ $startIdx=$i; break } }
  $fs='NO_BLOCK'
  if($startIdx -ge 0){
    $endIdx=[Math]::Min($lines.Count-1,$startIdx+40)
    for($j=$startIdx+1;$j -lt $endIdx;$j++){ if($lines[$j] -match '^\s*Window\s*#?\d*\s*Window\{' -or $lines[$j] -match '^\s*WindowState\{'){ $endIdx=$j-1; break } }
    $block=($lines[$startIdx..([Math]::Max($startIdx,$endIdx))] -join "`n")
    $flagLines=[regex]::Matches($block,'(?m)^\s*(fl|pfl|flags)=.*$') | ForEach-Object { $_.Value.Trim() }
    foreach($fl in $flagLines){ L "${label}_WINDOWFLAGRAW: $fl" }
    if($flagLines.Count -gt 0){ $joined=$flagLines -join ' '
      $fs = if($joined -match '(?<![\w])KEEP_SCREEN_ON(?![\w])'){'true'}else{'false'}
      L "${label}_WINDOW_KEEP_SCREEN_ON=$fs" } else { L "${label}_WINDOW_NO_FLAGLINE_IN_BLOCK" }
  }
  $p = Sh "dumpsys power"
  L "${label}_POWER_mWakefulness=$([regex]::Match($p,'mWakefulness=(\w+)').Groups[1].Value) mHoldingDisplaySuspendBlocker=$([regex]::Match($p,'mHoldingDisplaySuspendBlocker=(\w+)').Groups[1].Value)"
  return $fs
}
function D1Lines($label){
  $l = Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
  foreach($x in [regex]::Matches($l,'(?m)^.*D1 applyKeepScreenOn.*$')){ L "${label}_D1: $($x.Value.Trim())" }
  foreach($x in [regex]::Matches($l,'(?m)^.*/message.*$')){ L "${label}_MSG: $($x.Value.Trim())" }
}

L "########## F13 $Tag (NOR8 control) START $(Get-Date -Format o) ##########"
$md5=(Get-FileHash $Apk -Algorithm MD5).Hash.ToLower()
L "APK_PATH=$Apk"
L "APK_MD5=$md5"
L "APK_SIZE=$((Get-Item $Apk).Length)"
A @('shell','wm','size','1080x1600') | Out-Null
A @('shell','wm','density','420') | Out-Null
Start-Sleep -Seconds 2
L "WM_SIZE=$(A @('shell','wm','size'))"
L "WM_DENSITY=$(A @('shell','wm','density'))"
A @('reverse','tcp:4095','tcp:4095') | Out-Null
L "REVERSE=$(A @('reverse','--list'))"
A @('shell','pm','clear',$PKG) | Out-Null
L "INSTALL=$(A @('install','-r','-d',$Apk))"
$baseRaw = Sh "pm path $PKG"
L "PMPATH_FULL_RAW=[$baseRaw]"
$bm=[regex]::Match($baseRaw,'(/data/app/[^|\s]+/base\.apk)')
$base=$bm.Groups[1].Value
L "DEVICE_BASE_APK_PATH=[$base]"
if($base){
  L "LS_CHECK=$(Sh "ls -l $base")"
  $dm=Sh "md5sum $base"
  L "MDSUM_RAW=[$dm]"
  $devmd5=([regex]::Match($dm,'([0-9a-fA-F]{32})')).Groups[1].Value
  L "DEVICE_MD5=$devmd5"
  L "DEVICE_MD5_MATCH=$($devmd5 -eq $md5)"
} else { L "DEVICE_MD5=NO_PATH"; L "DEVICE_MD5_MATCH=False" }

A @('logcat','-c') | Out-Null
A @('shell','am','start','-n',$ACT) | Out-Null
Start-Sleep -Seconds 10
$d = DumpXml "seed1"
$et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
L "PASSWORD_FIELDS=$($et.Count)"
if($et.Count -ge 1){
  $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2); $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
  L "PASSWORD_TAP=($cx,$cy)"
  Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1500
  foreach($ch in 'opencode'.ToCharArray()){ Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 280 }
  L "PASSWORD_TYPED"
  $d2 = DumpXml "seed2"
  $btn=$null
  foreach($m in [regex]::Matches($d2,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')){
    $x1=[int]$m.Groups[1].Value;$y1=[int]$m.Groups[2].Value;$x2=[int]$m.Groups[3].Value;$y2=[int]$m.Groups[4].Value
    if($y1 -ge 870 -and $x1 -ge 600){ $btn=[pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2)} } }
  if($btn){ Sh "input tap $($btn.cx) $($btn.cy)" | Out-Null; L "SEED_SAVE_TAPPED=($($btn.cx),$($btn.cy))" } else { L "SEED_SAVE_TAPPED=NOT_FOUND" }
} else { L "PASSWORD_FIELDS=0 (no seed screen) " }
Start-Sleep -Seconds 16

# ---- wait for render ----
for($w=0;$w -lt 12;$w++){
  $d0 = DumpXml "v4a"
  $nn = Nodes $d0
  $rr = @($nn | Where-Object { $_.click -eq 'true' -and $_.text -ne '' -and $_.cls -match 'Button' -and $_.x2 -gt 0 })
  L "RENDER_WAIT try=$w rows=$($rr.Count)"
  if($rr.Count -ge 3){ break }
  Start-Sleep -Seconds 4
}
$lg = Sh "logcat -d -s OcWebHost OcPerfJS OcWebHost/Proxy"
$pm=[regex]::Match($lg,'listening on http://127\.0\.0\.1:(\d{4,5})')
$pp=if($pm.Success){[int]$pm.Groups[1].Value}else{0}
$sids=([regex]::Matches($lg,'/api/session/(ses_[A-Za-z0-9]+)')|ForEach-Object{$_.Groups[1].Value}|Sort-Object -Unique)
$authfail=([regex]::Matches($lg,'AUTH FAILED')).Count
L "PRE_PROXY_PORT=$pp AUTHFAILED=$authfail PROBE_OK=$($lg -match 'probe OK') FIRSTCONTENT=$($lg -match 'firstContent')"
L "PRE_SESSION_IDS=$(@($sids).Count) -> $(@($sids) -join ', ')"
L "PRE_LIST_ROWS=$($rr.Count)"
$preOk = ((@($sids).Count -ge 1) -and ($authfail -eq 0) -and ($pp -gt 0) -and ($rr.Count -ge 1))
L "PRECONDITION_PASS=$preOk"

$target = $rr | Where-Object { $_.y1 -ge 250 -and $_.y1 -le 1000 } | Select-Object -First 1
if(-not $target){ $target = $rr | Select-Object -First 1 }
if($target){
  L "STEP_A_SESSION_ROW text=[$($target.text)] bounds=[$($target.x1),$($target.y1)][$($target.x2),$($target.y2)] center=($($target.cx),$($target.cy))"
  L "STEP_A_TAP_CMD=adb -s $S shell input tap $($target.cx) $($target.cy)"
  Sh "input tap $($target.cx) $($target.cy)" | Out-Null
  L "STEP_A_TAPPED=REAL_UI_TAP (session row)" } else { L "STEP_A_TAPPED=NO_ROW_FOUND" }
Start-Sleep -Seconds 5
A @('shell','input','keyevent','111') | Out-Null
Start-Sleep -Milliseconds 700
$d1 = DumpXml "v4b"
$n1 = Nodes $d1
$ets = $n1 | Where-Object { $_.cls -match 'EditText' }
L "STEP_B_EDITTEXTS=$($ets.Count)"
foreach($e in $ets){ L "STEP_B_ET bounds=[$($e.x1),$($e.y1)][$($e.x2),$($e.y2)] center=($($e.cx),$($e.cy)) text=[$($e.text)]" }
$clicks = $n1 | Where-Object { $_.click -eq 'true' }
L "STEP_B_CLICKABLES=$($clicks.Count)"
foreach($c in $clicks){ L "STEP_B_CLICK cls=$($c.cls) text=[$($c.text)] bounds=[$($c.x1),$($c.y1)][$($c.x2),$($c.y2)] center=($($c.cx),$($c.cy))" }
$comp = $ets | Where-Object { $_.y1 -ge 900 } | Select-Object -First 1
if(-not $comp){ L "STEP_C_COMPOSER=NOT_FOUND_IN_BOTTOM_BAND" } else {
  L "STEP_C_COMPOSER bounds=[$($comp.x1),$($comp.y1)][$($comp.x2),$($comp.y2)] center=($($comp.cx),$($comp.cy))"
  L "STEP_C_FOCUS_TAP_CMD=adb -s $S shell input tap $($comp.cx) $($comp.cy)"
  Sh "input tap $($comp.cx) $($comp.cy)" | Out-Null
  Start-Sleep -Milliseconds 1200
  L "STEP_C_COMPOSER_FOCUSED=REAL_UI_TAP"
  foreach($w2 in @("f13","uitap")){ Sh "input text $w2" | Out-Null; Start-Sleep -Milliseconds 250 }
  L "STEP_C_TYPED=f13 uitap" }
$d2 = DumpXml "v4c"
$n2 = Nodes $d2
$clicks2 = $n2 | Where-Object { $_.click -eq 'true' }
L "STEP_D_CLICKABLES=$($clicks2.Count)"
foreach($c in $clicks2){ L "STEP_D_CLICK cls=$($c.cls) text=[$($c.text)] bounds=[$($c.x1),$($c.y1)][$($c.x2),$($c.y2)] center=($($c.cx),$($c.cy))" }
$send=$null; $how=''
foreach($c in $clicks2){ if($c.desc -match '(?i)send|发送'){ $send=$c; $how='content-desc'; break } }
if(-not $send){ foreach($c in $clicks2){ if($c.text -match '(?i)^\s*(send|发送)\s*$'){ $send=$c; $how='text'; break } } }
if(-not $send){ $band=$clicks2 | Where-Object { $_.y1 -ge 950 -and $_.cx -ge 700 -and ($_.x2-$_.x1) -le 400 }
  if($band){ $send=$band | Sort-Object -Property cx -Descending | Select-Object -First 1; $how='band-bottom-right' } }
$t0=Get-Date
if($send){
  L "STEP_D_SEND_PICKED how=$how cls=$($send.cls) text=[$($send.text)] bounds=[$($send.x1),$($send.y1)][$($send.x2),$($send.y2)] center=($($send.cx),$($send.cy))"
  L "STEP_D_TAP_CMD=adb -s $S shell input tap $($send.cx) $($send.cy)"
  Sh "input tap $($send.cx) $($send.cy)" | Out-Null
  L "STEP_D_SEND_TAPPED=REAL_UI_TAP on the official UI send button (NOT curl/proxy injection)" } else { L "STEP_D_SEND_TAPPED=NOT_FOUND" }
L "TAP_AT=$($t0.ToString('HH:mm:ss.fff'))"
$roundSeen='false'
for($i=0;$i -lt 8;$i++){ Start-Sleep -Seconds 2; $l2=Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
  if($l2 -match 'roundActive=true'){ $roundSeen='true'; L "ROUND_STARTED_BY_UI_TAP=YES (logcat roundActive=true) t+$($i*2+2)s"; break } }
if($roundSeen -eq 'false'){ L "ROUND_STARTED_BY_UI_TAP=NO (16s polling, no roundActive=true)" }
$lg3 = Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
foreach($x in [regex]::Matches($lg3,'(?m)^.*POST.*/message.*$')){ L "POST_MSG_EVIDENCE: $($x.Value.Trim())" }
foreach($x in [regex]::Matches($lg3,'(?m)^.*D1 roundActive=.*$')){ L "D1_ROUNDACTIVE: $($x.Value.Trim())" }
$seg0 = KeepState "SEG0_idle"
$raise='false'
for($i=0;$i -lt $SegSamples;$i++){ Start-Sleep -Seconds 2
  $fs = KeepState ("SEG1_s{0:d2}_tplus{1}s" -f $i, ($i*2+2)); if($fs -eq 'true'){ $raise='true' } }
$seg1=$raise
D1Lines "SEG1" | Out-Null
L "SEG1_RAISED_SEEN=$raise"
L "SEG2 wait 85s"; Start-Sleep -Seconds 85
$seg2 = KeepState "SEG2_afterEnd"; D1Lines "SEG2" | Out-Null
L "SEG3 wait 95s"; Start-Sleep -Seconds 95
$seg3 = KeepState "SEG3_tplus95s"; D1Lines "SEG3" | Out-Null
L "SUMMARY_$Tag PRECONDITION=$preOk ROUND_STARTED=$roundSeen SEG0=$seg0 SEG1_raised=$seg1 SEG2_afterEnd=$seg2 SEG3=$seg3"
L "VERDICT_$Tag raised=$seg1 release=$($seg2 -eq 'false') plus90=$($seg3 -eq 'false')"
L "########## F13 $Tag END $(Get-Date -Format o) ##########"
