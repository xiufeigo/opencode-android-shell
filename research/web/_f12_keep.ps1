param(
  [Parameter(Mandatory=$true)][string]$Tag,
  [Parameter(Mandatory=$true)][string]$Apk
)
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S="emulator-6310"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f12"
$LOG="$OUT\$Tag.log"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"),$m; Add-Content -Path $LOG -Value $s -Encoding utf8; Write-Output $s }

L "########## F12 $Tag START $(Get-Date -Format o) ##########"
$md5 = (Get-FileHash $Apk -Algorithm MD5).Hash.ToLower()
$sz  = (Get-Item $Apk).Length
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

$base = (Sh "pm path $PKG") -replace '.*package:',''
$dm = Sh "md5sum $base"
$devmd5 = ([regex]::Match($dm,'[0-9a-fA-F]{32}')).Value
L "DEVICE_BASE_APK=$base"
L "DEVICE_MD5=$devmd5"
L "DEVICE_MD5_MATCH=$($devmd5 -eq $md5)"

A @('logcat','-c') | Out-Null
A @('shell','am','start','-n',$ACT) | Out-Null
Start-Sleep -Seconds 8
A @('shell','uiautomator','dump','/sdcard/d.xml') | Out-Null
$d = Sh "cat /sdcard/d.xml"
$et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
L "PASSWORD_FIELDS=$($et.Count)"
if ($et.Count -ge 1) {
  $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2); $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
  Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1500
  foreach ($ch in 'opencode'.ToCharArray()) { Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 300 }
  L "PASSWORD_TYPED"
}
A @('shell','uiautomator','dump','/sdcard/d2.xml') | Out-Null
$d2 = Sh "cat /sdcard/d2.xml"
$btn = $null
foreach ($m in [regex]::Matches($d2,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
  $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
  if ($y1 -ge 870 -and $x1 -ge 600) { $btn = [pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2)} }
}
if ($btn) { Sh "input tap $($btn.cx) $($btn.cy)" | Out-Null; L "SEED_SAVE_TAPPED=($($btn.cx),$($btn.cy))" }
else { L "SEED_SAVE_TAPPED=NOT_FOUND" }
Start-Sleep -Seconds 18

# ---- proxy port: from the app's own "listening on http://127.0.0.1:PORT" line ----
$lg = Sh "logcat -d -s OcWebHost OcPerfJS OcWebHost/Proxy"
$pm=[regex]::Match($lg,'listening on http://127\.0\.0\.1:(\d{4,5})')
$pp = if($pm.Success){[int]$pm.Groups[1].Value}else{0}
L "PROXY_LISTEN_LINE=$(if($pm.Success){$pm.Value}else{'NOT_FOUND'})"
L "PROXY_PORT=$pp"

$authfail = ([regex]::Matches($lg,'AUTH FAILED')).Count
$probeok  = $lg -match 'probe OK'
$gets = ([regex]::Matches($lg,'GET (/api/[^\s|"]*)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
$sids = ([regex]::Matches($lg,'/api/session/(ses_[A-Za-z0-9]+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
L "PRE_AUTHFAILED=$authfail"
L "PRE_PROBE_OK=$probeok"
L "PRE_API_GETS_UNIQUE=$(@($gets).Count)"
foreach ($g in $gets) { L "PRE_GET $g" }
L "PRE_SESSION_IDS=$(@($sids).Count) -> $(@($sids) -join ', ')"
L "PRE_FIRSTCONTENT=$($lg -match 'firstContent')"
L "PRECONDITION_PASS=$((@($sids).Count -ge 1) -and $probeok -and ($authfail -eq 0) -and ($pp -gt 0))"

# ---- keep-screen-on sampler ----
# Android 15 (SDK35) prints window flags as a SYMBOLIC list on the mAttrs "fl=" line,
# e.g. "fl=LAYOUT_IN_SCREEN ... KEEP_SCREEN_ON ...". The older "flags=0x..." form
# does not appear here, so we detect the KEEP_SCREEN_ON name in the fl=/pfl= lines
# of OUR window block. Independent corroboration comes from dumpsys power.
function KeepState($label) {
  $w = (& $adb -s $S shell dumpsys window windows 2>&1 | Out-String)
  $lines = $w -split "`r?`n"
  $startIdx = -1
  for ($i=0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'Window\{[^}]*com\.xiufeigo\.opencodeshell') { $startIdx = $i; break }
  }
  $fs='NO_BLOCK'
  if ($startIdx -ge 0) {
    $endIdx = [Math]::Min($lines.Count-1, $startIdx+40)
    for ($j=$startIdx+1; $j -lt $endIdx; $j++) {
      if ($lines[$j] -match '^\s*Window\s*#?\d*\s*Window\{' -or $lines[$j] -match '^\s*WindowState\{') { $endIdx = $j-1; break }
    }
    $block = ($lines[$startIdx..([Math]::Max($startIdx,$endIdx))] -join "`n")
    $flagLines = [regex]::Matches($block,'(?m)^\s*(fl|pfl|flags)=.*$') | ForEach-Object { $_.Value.Trim() }
    foreach ($fl in $flagLines) { L "${label}_WINDOWFLAGRAW: $fl" }
    if ($flagLines.Count -gt 0) {
      $joined = $flagLines -join ' '
      $fs = if ($joined -match '(?<![\w])KEEP_SCREEN_ON(?![\w])') { 'true' } else { 'false' }
      # also record numeric form if this build ever emits it
      $nm = [regex]::Match($joined,'flags=(0x[0-9a-fA-F]+)')
      if ($nm.Success) {
        $v=[Convert]::ToInt64($nm.Groups[1].Value.Substring(2),16)
        L "${label}_WINDOW_flagsNum=0x$($nm.Groups[1].Value.Substring(2)) 0x80bit=$((($v -band 0x80) -ne 0))"
      }
      L "${label}_WINDOW_KEEP_SCREEN_ON=$fs"
    } else { L "${label}_WINDOW_NO_FLAGLINE_IN_BLOCK" }
  }
  $p = Sh "dumpsys power"
  $mw=[regex]::Match($p,'mWakefulness=(\w+)')
  $hs=[regex]::Match($p,'mHoldingDisplaySuspendBlocker=(\w+)')
  L "${label}_POWER_mWakefulness=$($mw.Groups[1].Value) mHoldingDisplaySuspendBlocker=$($hs.Groups[1].Value)"
  $pr=([regex]::Matches($p,'(?m)^.*KEEP_SCREEN_ON.*$'))
  foreach($x in $pr){ L "${label}_POWER_RAW: $($x.Value.Trim())" }
  return $fs
}
function D1Lines($label) {
  $l = Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
  $d = [regex]::Matches($l,'(?m)^.*D1 applyKeepScreenOn.*$')
  foreach($x in $d){ L "${label}_D1: $($x.Value.Trim())" }
  $b = [regex]::Matches($l,'(?m)^.*D1 backstop.*$')
  foreach($x in $b){ L "${label}_BACKSTOP: $($x.Value.Trim())" }
  $ra = [regex]::Matches($l,'(?m)^.*D1 roundActive=.*$')
  foreach($x in $ra){ L "${label}_D1_ROUNDACTIVE: $($x.Value.Trim())" }
  return $d.Count
}

A @('logcat','-c') | Out-Null
L "SEG0 baseline (idle, no round)"
$seg0 = KeepState "SEG0_idle"
D1Lines "SEG0" | Out-Null

L "SEG1 raising: POST real message to loopback proxy /api/session/<sid>/message"
$sid = @($sids)[0]
$t0 = Get-Date
L "SEG1_SENT_AT=$($t0.ToString('HH:mm:ss.fff')) sid=$sid proxyPort=$pp"
if ($pp -gt 0 -and $sid) {
  $body = '{"parts":[{"type":"text","text":"f12 keep-screen-on probe reply with one short line"}]}'
  $code = Sh "curl -s -m 20 -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary '$body' http://127.0.0.1:$pp/api/session/$sid/message"
  L "SEG1_POST_HTTP=$code"
}
L "SEG1 sampling every 2s for 36s (round in progress)"
$raiseSeen = 'false'
for ($i=0; $i -lt 18; $i++) {
  Start-Sleep -Seconds 2
  $fs = KeepState ("SEG1_s{0:d2}_tplus{1}s" -f $i, ($i*2+2))
  if ($fs -eq 'true') { $raiseSeen = 'true' }
}
$seg1 = $raiseSeen
D1Lines "SEG1" | Out-Null
L "SEG1_RAISED_SEEN=$raiseSeen"

L "SEG2 waiting 85s for round end + release"
Start-Sleep -Seconds 85
$seg2 = KeepState "SEG2_afterEnd"
D1Lines "SEG2" | Out-Null

L "SEG3 sleeping 95s to cover the +90s D1 backstop window"
Start-Sleep -Seconds 95
$seg3 = KeepState "SEG3_tplus95s"
D1Lines "SEG3" | Out-Null

L "SUMMARY_$Tag SEG0_idle=$seg0 SEG1_raised=$seg1 SEG2_afterEnd=$seg2 SEG3_tplus95s=$seg3"
L "VERDICT_$Tag raised=$seg1 release=$($seg2 -eq 'false') plus90=$($seg3 -eq 'false')"
L "########## F12 $Tag END $(Get-Date -Format o) ##########"
