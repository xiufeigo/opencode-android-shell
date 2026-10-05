param([Parameter(Mandatory=$true)][string]$Tag)
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

L "########## F12 $Tag (retry) START $(Get-Date -Format o) ##########"
A @('shell','am','force-stop',$PKG) | Out-Null
A @('logcat','-c') | Out-Null
L "LAUNCH=$(A @('shell','am','start','-n',$ACT))"
Start-Sleep -Seconds 10

A @('shell','uiautomator','dump','/sdcard/d.xml') | Out-Null
$d = Sh "cat /sdcard/d.xml"
$et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
L "PASSWORD_FIELDS=$($et.Count)"
if ($et.Count -ge 1) {
  $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2); $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
  Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1500
  foreach ($ch in 'opencode'.ToCharArray()) { Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 300 }
  L "PASSWORD_TYPED"
} else { L "PASSWORD_FIELD_MISSING" }
A @('shell','uiautomator','dump','/sdcard/d2.xml') | Out-Null
$d2 = Sh "cat /sdcard/d2.xml"
$btn = $null
foreach ($m in [regex]::Matches($d2,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
  $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
  if ($y1 -ge 870 -and $x1 -ge 600) { $btn = [pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2)} }
}
if ($btn) { Sh "input tap $($btn.cx) $($btn.cy)" | Out-Null; L "SEED_SAVE_TAPPED=($($btn.cx),$($btn.cy))" } else { L "SEED_SAVE_TAPPED=NOT_FOUND" }
Start-Sleep -Seconds 20

$lg = Sh "logcat -d -s OcWebHost OcPerfJS OcWebHost/Proxy"
$pm=[regex]::Match($lg,'listening on http://127\.0\.0\.1:(\d{4,5})')
$pp = if($pm.Success){[int]$pm.Groups[1].Value}else{0}
$sids = ([regex]::Matches($lg,'/api/session/(ses_[A-Za-z0-9]+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
$authfail = ([regex]::Matches($lg,'AUTH FAILED')).Count
L "PROXY_LISTEN_LINE=$(if($pm.Success){$pm.Value}else{'NOT_FOUND'})"
L "PROXY_PORT=$pp"
L "PRE_AUTHFAILED=$authfail"
L "PRE_PROBE_OK=$($lg -match 'probe OK')"
L "PRE_SESSION_IDS=$(@($sids).Count) -> $(@($sids) -join ', ')"
L "PRECONDITION_PASS=$((@($sids).Count -ge 1) -and ($authfail -eq 0) -and ($pp -gt 0))"
if ($pp -le 0 -or @($sids).Count -eq 0) { L "ABORT: precondition not met"; L "########## F12 $Tag (retry) END ##########"; exit 1 }

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
    if ($flagLines.Count -gt 0) { $j2 = $flagLines -join ' '; $fs = if ($j2 -match '(?<![\w])KEEP_SCREEN_ON(?![\w])') { 'true' } else { 'false' }; L "${label}_WINDOW_KEEP_SCREEN_ON=$fs" }
    else { L "${label}_WINDOW_NO_FLAGLINE_IN_BLOCK" }
  }
  $p = Sh "dumpsys power"
  L "${label}_POWER_mWakefulness=$([regex]::Match($p,'mWakefulness=(\w+)').Groups[1].Value)"
  return $fs
}
function D1Lines($label) {
  $l = Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
  foreach ($x in [regex]::Matches($l,'(?m)^.*D1 applyKeepScreenOn[^\|]*$')) { L "${label}_D1: $($x.Value.Trim())" }
  foreach ($x in [regex]::Matches($l,'(?m)^.*D1 roundActive=[^\|]*$')) { L "${label}_D1_ROUNDACTIVE: $($x.Value.Trim())" }
}

A @('logcat','-c') | Out-Null
$seg0 = KeepState "SEG0_idle"
D1Lines "SEG0" | Out-Null
L "SEG0_RESULT=$seg0"

$sid = @($sids)[0]
L "SEG1_SENT_AT=$((Get-Date).ToString('HH:mm:ss.fff')) sid=$sid proxyPort=$pp"
$body = '{"parts":[{"type":"text","text":"f12 keep-screen-on probe reply with one short line"}]}'
L "SEG1_POST_HTTP=$(Sh "curl -s -m 20 -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary '$body' http://127.0.0.1:$pp/api/session/$sid/message")"
$raiseSeen='false'
for ($i=0; $i -lt 15; $i++) { Start-Sleep -Seconds 2; $fs = KeepState ("SEG1_s{0:d2}" -f $i); if ($fs -eq 'true') { $raiseSeen='true' } }
D1Lines "SEG1" | Out-Null
L "SEG1_RAISED_SEEN=$raiseSeen"

Start-Sleep -Seconds 80
$seg2 = KeepState "SEG2_afterEnd"
D1Lines "SEG2" | Out-Null
Start-Sleep -Seconds 95
$seg3 = KeepState "SEG3_tplus95s"
D1Lines "SEG3" | Out-Null

L "SUMMARY_$Tag SEG0_idle=$seg0 SEG1_raised=$raiseSeen SEG2_afterEnd=$seg2 SEG3_tplus95s=$seg3"
L "########## F12 $Tag (retry) END $(Get-Date -Format o) ##########"
