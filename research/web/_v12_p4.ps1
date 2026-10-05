$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p4.log" -Value $s -Encoding utf8; Write-Output $s }
function FindSaveBtn($xml) {
  $c=@()
  foreach ($m in [regex]::Matches($xml,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
    if ($y1 -ge 870 -and $x1 -ge 600) { $c += [pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2);x1=$x1;y1=$y1;x2=$x2;y2=$y2} }
  }
  if($c.Count -eq 0){return $null}; return ($c | Sort-Object x1 -Descending | Select-Object -First 1)
}
function UiDump($tag) {
  A @('shell','uiautomator','dump','/sdcard/u.xml') | Out-Null
  $d = Sh "cat /sdcard/u.xml"
  Add-Content -Path "$OUT\ui_$tag.xml" -Value $d -Encoding utf8
  A @('shell','screencap','-p',"/sdcard/$tag.png") | Out-Null
  A @('pull',"/sdcard/$tag.png","$OUT\$tag.png") | Out-Null
  return $d
}
function VisText($xml){
  $s = ($xml | Out-String)
  $m = [regex]::Matches($s,'text="([^"]+)"')
  $vals = @()
  foreach($x in $m){ $v=$x.Groups[1].Value; if($v -ne '' -and $v -notmatch 'resource-id'){ $vals += $v } }
  return (($vals | Select-Object -Unique) -join ' | ')
}
function Seed($pw,$tag) {
  A @('shell','pm','clear',$PKG) | Out-Null
  A @('logcat','-c') | Out-Null
  A @('shell','am','start','-n',$ACT) | Out-Null
  Start-Sleep -Seconds 7
  A @('shell','uiautomator','dump','/sdcard/d.xml') | Out-Null
  $d = Sh "cat /sdcard/d.xml"
  $et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if ($et.Count -lt 1) { $et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
  if ($et.Count -ge 1) {
    $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2); $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
    Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1400
    foreach ($ch in $pw.ToCharArray()) { Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 260 }
  }
  A @('shell','uiautomator','dump','/sdcard/d2.xml') | Out-Null
  $d2 = Sh "cat /sdcard/d2.xml"
  $b = FindSaveBtn $d2
  if ($b) { Sh "input tap $($b.cx) $($b.cy)" | Out-Null; L "  seed[$tag]: SAVE tapped ($($b.cx),$($b.cy))" } else { L "  seed[$tag]: SAVE_NOT_FOUND" }
  Start-Sleep -Seconds 12
}
function KeepState($label) {
  $w = Sh "dumpsys window windows"
  $p = Sh "dumpsys power"
  $attrs=[regex]::Match($w,'(?ms)Window\{[^}]*com\.xiufeigo\.opencodeshell[^}]*\}.*?(?=\r?\n\s*Window\{|\z)')
  $fs='UNKNOWN'
  if($attrs.Success){ $m2=[regex]::Match($attrs.Value,'flags=([0-9xXa-fA-F]+)')
    if($m2.Success){ $fl=$m2.Groups[1].Value; $v=[Convert]::ToInt64($fl,16); $fs=if(($v -band 0x80) -ne 0){'true'}else{'false'}
      L "${label} WINDOW_FLAGS=0x${fl} flagSet(0x80)=${fs} (ts=$(Get-Date -Format 'HH:mm:ss'))" } }
  $hits=([regex]::Matches($w,'KEEP_SCREEN_ON')).Count
  $mw=[regex]::Match($p,'mWakefulness=(\w+)')
  L "${label} dumpsys_window KEEP_SCREEN_ON_hits=$hits | dumpsys_power mWakefulness=$($mw.Groups[1].Value) (ts=$(Get-Date -Format 'HH:mm:ss'))"
  return $fs
}
function ProxyPort() {
  $lg = Sh "logcat -d -s OcPerfJS OcWebHost"
  $m=[regex]::Match($lg,'http://127\.0\.0\.1:(\d{4,5})/api/')
  if($m.Success){return [int]$m.Groups[1].Value}; return -1
}

# =============== E1: backend unreachable (wrong-port method) ===============
L "########## E1 backend unreachable ##########"
L "REVERSE_WRONG: $(A @('reverse','tcp:4095','tcp:4099'))"
L "REVERSE_LIST: $(A @('reverse','--list'))"
Seed "opencode" "E1"
$le1 = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_E1.txt" -Value $le1 -Encoding utf8
$mu=[regex]::Match($le1,'(?m)^.*probe unreachable.*$'); L "E1_probeLine: $(if($mu.Success){$mu.Value.Trim()}else{'ABSENT'})"
L "E1_probeOK=$($le1 -match 'probe OK code=200')"
$pp=ProxyPort; L "E1_proxyPort=$pp"
if($pp -gt 0){
  $body = Sh "curl -s -m 5 http://127.0.0.1:$pp/api/session" 
  L "E1_direct_api_probe_len=$($body.Length)"
}
$ue1=UiDump "E1_unreachable"
L "E1_VISIBLE_TEXTS: $(VisText $ue1)"
L "E1_CONTAINS_127_0_0_1_4095=$($ue1 -match '127\.0\.0\.1:4095') (expect False)"
L "E1_CONTAINS_PORT_4095=$($ue1 -match '4095') (expect False)"
L "E1_FRIENDLY=$($ue1 -match '请检查服务器地址|检查地址与网络|重试|连不上')"
# also read the served HTML body text (proxy redacts)
if($pp -gt 0){ $html = Sh "curl -s -m 5 http://127.0.0.1:$pp/"; Add-Content -Path "$OUT\E1_served.html.txt" -Value $html -Encoding utf8
  L "E1_SERVED_HTML_len=$($html.Length) contains_4095=$($html -match '4095') contains_127001=$($html -match '127\.0\.0\.1')"
  L "E1_SERVED_HTML_TEXT: $(((($html -replace '<[^>]+>',' ') -replace '\s+',' ').Trim()).Substring(0,[Math]::Min(500,(($html -replace '<[^>]+>',' ') -replace '\s+',' ').Trim().Length)))" }

# restore correct reverse
L "REVERSE_FIX: $(A @('reverse','tcp:4095','tcp:4095'))"

# =============== E2: wrong password ===============
L "########## E2 wrong password ##########"
Seed "wrongpass" "E2"
$le2 = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_E2.txt" -Value $le2 -Encoding utf8
$ma=[regex]::Match($le2,'(?m)^.*AUTH FAILED.*$'); L "E2_AUTHFAILED: $(if($ma.Success){$ma.Value.Trim()}else{'ABSENT'})"
L "E2_probeOK=$($le2 -match 'probe OK code=200') (expect False)"
$pp2=ProxyPort; L "E2_proxyPort=$pp2"
$ue2=UiDump "E2_wrongpw"
L "E2_VISIBLE_TEXTS: $(VisText $ue2)"
L "E2_SAYS_AUTHFAIL=$($ue2 -match '用户名或口令不对') (expect True)"
L "E2_HAS_RETRY=$($ue2 -match '重试|重新输入|继续')"
L "E2_ECHOES_PASSWORD=$($ue2 -match 'wrongpass') (expect False)"
if($pp2 -gt 0){ $html2=Sh "curl -s -m 5 http://127.0.0.1:$pp2/"; Add-Content -Path "$OUT\E2_served.html.txt" -Value $html2 -Encoding utf8
  L "E2_SERVED_len=$($html2.Length) contains_wrongpass=$($html2 -match 'wrongpass') contains_4095=$($html2 -match '4095')" }

# =============== E3: correct password, no dialog ===============
L "########## E3 correct password, expect direct official UI ##########"
Seed "opencode" "E3"
$le3 = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_E3.txt" -Value $le3 -Encoding utf8
L "E3_probeOK=$($le3 -match 'probe OK code=200')  E3_AUTH_FAILED=$(([regex]::Matches($le3,'AUTH FAILED')).Count)"
$pp3=ProxyPort
$g3=([regex]::Matches($le3,'GET http://127\.0\.0\.1:4095/api/[^ ]*')|ForEach-Object{$_.Value}|Select-Object -Unique)
L "E3_API_GETS_UNIQUE=$($g3.Count)  proxyPort=$pp3"
$ue3=UiDump "E3_official"
L "E3_VISIBLE_TEXTS: $(VisText $ue3)"
L "E3_HAS_AUTH_DIALOG=$($ue3 -match '用户名或口令不对') (expect False)"
L "E3_HAS_UNREACH=$($ue3 -match '连不上|检查服务器地址') (expect False)"
$fc=[regex]::Match($le3,'(?m)^.*M\.g1\.firstContent.*$'); L "E3_FIRSTCONTENT: $(if($fc.Success){$fc.Value.Trim()}else{'ABSENT'})"
$po=[regex]::Match($le3,'(?m)^.*J\.g1\.preflightOk.*$'); L "E3_PREFLIGHT: $(if($po.Success){$po.Value.Trim()}else{'ABSENT'})"
L "E3_blockedOldWebview=$(([regex]::Matches($le3,'blockedOldWebview')).Count) (expect 0)"
# data-arrived proof: real session ids + title
$sid=([regex]::Matches($le3,'/api/session/(ses_[A-Za-z0-9]+)')|ForEach-Object{$_.Groups[1].Value}|Select-Object -Unique)
L "E3_SESSION_IDS=$($sid -join ',') (n=$($sid.Count))"
$ttl=[regex]::Match($le3,'(?m)^.*title=.*$'); L "E3_TITLE_LINE: $(if($ttl.Success){$ttl.Value.Trim()}else{'ABSENT'})"
if($pp3 -gt 0){ $h3=Sh "curl -s -m 5 http://127.0.0.1:$pp3/"; L "E3_SERVED_len=$($h3.Length) has_opencode_title=$($h3 -match '<title>|opencode')" }
L "E3_PROXYPORT=$pp3" | Out-File "$OUT\proxy_port.txt" -Encoding utf8

# =============== first-screen 3 cold runs ===============
L "########## first-screen 3 cold runs ##########"
$times=@()
for($i=1;$i -le 3;$i++){
  A @('shell','am','force-stop',$PKG) | Out-Null; Start-Sleep -Seconds 3
  A @('logcat','-c') | Out-Null
  A @('shell','am','start','-n',$ACT) | Out-Null
  Start-Sleep -Seconds 16
  $lg=Sh "logcat -d -s OcPerf OcPerfJS"; Add-Content -Path "$OUT\log_run$i.txt" -Value $lg -Encoding utf8
  $m=[regex]::Match($lg,'M\.g1\.firstContent[^\r\n]*?t=(\d+)ms')
  $pf=[regex]::Match($lg,'J\.g1\.preflightOk[^\r\n]*?t=(\d+)ms')
  if($m.Success){ $times+=[int]$m.Groups[1].Value; L "RUN${i}_FIRSTCONTENT_MS=$($m.Groups[1].Value)" } else { L "RUN${i}_FIRSTCONTENT=NOT_FOUND" }
  L "RUN${i}_preflightOk=$($pf.Success) blockedOldWebview=$(([regex]::Matches($lg,'blockedOldWebview')).Count)"
  A @('shell','screencap','-p',"/sdcard/run$i.png") | Out-Null; A @('pull',"/sdcard/run$i.png","$OUT\run$i.png") | Out-Null
}
if($times.Count -ge 1){ $s=$times|Sort-Object; $med=if($s.Count%2){$s[[int]($s.Count/2)]}else{[int](($s[$s.Count/2-1]+$s[$s.Count/2])/2)}; L "FIRSTSCREEN_ALL_MS=$($times -join ',') MEDIAN=${med} MIN=$($s[0]) MAX=$($s[-1]) n=$($times.Count)" }
L "=== P4 DONE ==="
