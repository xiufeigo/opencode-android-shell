$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p4b.log" -Value $s -Encoding utf8; Write-Output $s }
function FindSaveBtn($xml) {
  $c=@()
  foreach ($m in [regex]::Matches($xml,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
    if ($y1 -ge 870 -and $x1 -ge 600) { $c += [pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2)} }
  }
  if($c.Count -eq 0){return $null}; return ($c | Sort-Object x1 -Descending | Select-Object -First 1)
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
  Start-Sleep -Seconds 13
}
# proxy port from the "listening on http://127.0.0.1:NNNNN" line
function GetProxyPort() {
  $lg = Sh "logcat -d -s OcPerf OcWebHost OcWebHost/Proxy OcPerfJS"
  $m=[regex]::Match($lg,'listening on http://127\.0\.0\.1:(\d{4,5})')
  if($m.Success){ return [int]$m.Groups[1].Value }
  $m2=[regex]::Match($lg,'port=(\d{4,5})')
  if($m2.Success){ return [int]$m2.Groups[1].Value }
  return -1
}
function StripHtml($h){ return (($h -replace '(?s)<script.*?</script>','') -replace '(?s)<style.*?</style>','') -replace '<[^>]+>',' ' -replace '\s+',' ' }

# ============ E1 redo: wrong-port + fetch the SERVED friendly page ============
L "########## E1 redo: served-HTML evidence ##########"
L "REVERSE_WRONG: $(A @('reverse','tcp:4095','tcp:4099'))"
L "REVERSE_LIST: $(A @('reverse','--list'))"
Seed "opencode" "E1b"
$lg = Sh "logcat -d -s OcPerf OcWebHost OcWebHost/Proxy OcPerfJS"
Add-Content -Path "$OUT\log_E1b.txt" -Value $lg -Encoding utf8
$mu=[regex]::Match($lg,'(?m)^.*probe unreachable.*$'); L "E1_PROBE_LINE: $(if($mu.Success){$mu.Value.Trim()}else{'ABSENT'})"
L "E1_probeOK=$($lg -match 'probe OK code=200')"
$pp = GetProxyPort
L "E1_PROXY_PORT=$pp"
if($pp -gt 0){
  $html = Sh "curl -s -m 8 http://127.0.0.1:$pp/"
  Add-Content -Path "$OUT\E1b_served.html" -Value $html -Encoding utf8
  $txt = StripHtml $html
  L "E1_SERVED_HTML_LEN=$($html.Length)"
  L "E1_SERVED_TEXT: $($txt.Trim())"
  L "E1_ASSERT_visibleText_has_127001_4095=$($txt -match '127\.0\.0\.1:4095') (expect False)"
  L "E1_ASSERT_visibleText_has_port_4095=$($txt -match '4095') (expect False)"
  L "E1_ASSERT_visibleText_has_friendly=$($txt -match '请检查服务器地址|检查地址与网络|重试|连不上|服务器地址')"
  L "E1_ASSERT_visibleText_has_retry_btn=$($txt -match '重试')"
  A @('shell','screencap','-p','/sdcard/E1b.png') | Out-Null
  A @('pull','/sdcard/E1b.png',"$OUT\E1b_unreachable.png") | Out-Null
  L "E1b_SCREENSHOT bytes=$((Get-Item "$OUT\E1b_unreachable.png" -ErrorAction SilentlyContinue).Length)"
}
A @('shell','uiautomator','dump','/sdcard/e1b.xml') | Out-Null
$u=Sh "cat /sdcard/e1b.xml"
L "E1_UIAUTOMATOR_texts_present=$($u -match 'text=\"[^\"]+\"')  (WebView text not exposed to uiautomator - expected)"
$pp | Out-File "$OUT\proxy_port_E1b.txt" -Encoding utf8

# restore
L "REVERSE_FIX: $(A @('reverse','tcp:4095','tcp:4095'))"
L "REVERSE_LIST_AFTER: $(A @('reverse','--list'))"

# ============ E2 redo: wrong password served HTML + dialog ============
L "########## E2 redo: wrong password ##########"
Seed "wrongpass" "E2b"
$lg2 = Sh "logcat -d -s OcPerf OcWebHost OcWebHost/Proxy OcPerfJS"
Add-Content -Path "$OUT\log_E2b.txt" -Value $lg2 -Encoding utf8
$ma=[regex]::Match($lg2,'(?m)^.*AUTH FAILED.*$'); L "E2_AUTHFAILED_LINE: $(if($ma.Success){$ma.Value.Trim()}else{'ABSENT'})"
L "E2_probeOK=$($lg2 -match 'probe OK code=200') (expect False)"
A @('shell','uiautomator','dump','/sdcard/e2b.xml') | Out-Null
$u2=Sh "cat /sdcard/e2b.xml"
Add-Content -Path "$OUT\ui_E2b.xml" -Value $u2 -Encoding utf8
A @('shell','screencap','-p','/sdcard/E2b.png') | Out-Null
A @('pull','/sdcard/E2b.png',"$OUT\E2b_wrongpw.png") | Out-Null
$vals=@(); foreach($m in [regex]::Matches($u2,'text="([^"]+)"')){ $v=$m.Groups[1].Value; if($v -ne '' -and $v -notmatch 'resource-id'){$vals+=$v} }
L "E2_DIALOG_TEXTS: $(($vals|Select-Object -Unique) -join ' | ')"
L "E2_SAYS_AUTHFAIL=$($u2 -match '用户名或口令不对') (expect True)"
L "E2_HAS_RETRY=$($u2 -match '重试|重新输入|继续') (expect True)"
L "E2_ECHOES_PASSWORD=$($u2 -match 'wrongpass') (expect False)"
L "E2_SCREENSHOT bytes=$((Get-Item "$OUT\E2b_wrongpw.png" -ErrorAction SilentlyContinue).Length)"
$pp2=GetProxyPort; L "E2_PROXY_PORT=$pp2"
$pp2 | Out-File "$OUT\proxy_port_E2b.txt" -Encoding utf8
L "=== P4b DONE ==="
