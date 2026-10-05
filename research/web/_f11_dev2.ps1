$ErrorActionPreference='Continue'
# F11 修正探针：① 错误态三态（经回环代理读**真实 DOM 文本**，uiautomator 看不见 WebView DOM）
#            ② 常亮三段（修正解析 + 取 D1 applyKeepScreenOn 真实行）
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6290"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f11"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path "$OUT\dev2.log" -Value $s -Encoding utf8; Write-Output $s }
function FindSaveBtn($xml) {
  $c=@()
  foreach ($m in [regex]::Matches($xml,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
    if ($y1 -ge 870 -and $x1 -ge 600) { $c += [pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2);x1=$x1} }
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
  Start-Sleep -Seconds 11
}
function ProxyPort() {
  $lg = Sh "logcat -d -s OcPerfJS OcWebHost"
  $m=[regex]::Match($lg,'http://127\.0\.0\.1:(\d{4,5})/api/')
  if($m.Success){return [int]$m.Groups[1].Value}; return -1
}
# 读回环代理真正吐给 WebView 的 HTML 文本（DOM 真身）
function DomText($port) {
  if($port -le 0){ return '' }
  $h = Sh "curl -s -m 6 http://127.0.0.1:$port/"
  Add-Content -Path "$OUT\dom_last.txt" -Value $h -Encoding utf8
  $t = (($h -replace '<script[\s\S]*?</script>',' ') -replace '<style[\s\S]*?</style>',' ') -replace '<[^>]+>',' '
  $t = ($t -replace '&nbsp;',' ' -replace '\s+',' ').Trim()
  return $t
}
# 修正的常亮探针：按 Window{...} 切块，逐块找本包窗口，再解 flags 的 0x80 位
function KeepState($label) {
  $w = Sh "dumpsys window windows"
  $blocks = [regex]::Matches($w,'Window\{[^|]*?(?=Window\{|$)')
  $found=$false; $fs='NO_WINDOW_BLOCK'
  foreach($b in $blocks){
    if($b.Value -match 'opencodeshell'){
      $found=$true
      $m2=[regex]::Match($b.Value,'flags=([0-9a-fA-Fx]+)')
      if($m2.Success){
        $hex=$m2.Groups[1].Value -replace 'x',''
        try{ $v=[Convert]::ToInt64($hex,16); $fs=if(($v -band 0x80) -ne 0){'true'}else{'false'} }catch{ $fs='PARSE_FAIL' }
        L "${label} windowFlags=0x$hex flagSet(0x80)=${fs} KEEP_SCREEN_ON_inblock=$($b.Value -match 'KEEP_SCREEN_ON')"
      } else { L "${label} windowBlock=FOUND_noFlagsField" }
      break
    }
  }
  if(-not $found){ L "${label} NO opencodeshell window block found (blocks=$($blocks.Count))" }
  $p = Sh "dumpsys power"
  $wm = ([regex]::Matches($p,'WindowManager')|Measure-Object).Count
  L "${label} dumpsys_power_WindowManager_wakelocks=$wm mWakefulness=$([regex]::Match($p,'mWakefulness=(\w+)').Groups[1].Value) ts=$(Get-Date -Format 'HH:mm:ss')"
  return $fs
}

L "=== F11 probe2 start $(Get-Date -Format o) ==="
L "R8 installed: $((Sh "pm path $PKG") -replace '.*package:','')"
L "REVERSE ok: $(A @('reverse','tcp:4095','tcp:4095'))"

# ═════ 常亮三段（idle 基线 + +90s；round-active 段另用 D1 日志判定）═════════════
L "########## K1 keep-screen tri-segment (R8) ##########"
A @('logcat','-c') | Out-Null
A @('shell','am','force-stop',$PKG) | Out-Null; Start-Sleep -Seconds 3
A @('shell','am','start','-n',$ACT) | Out-Null
Start-Sleep -Seconds 14
$k1 = KeepState "KSEG1_t0"
Start-Sleep -Seconds 30
$k2 = KeepState "KSEG2_t+30s"
Start-Sleep -Seconds 60
$k3 = KeepState "KSEG3_t+90s"
L "KEEP_SEGMENTS: t0=$k1 t+30=$k2 t+90=$k3"
$d1 = Sh "logcat -d | grep 'D1 applyKeepScreenOn' | tail -5"
L "D1_LINES: $(if($d1 -and $d1 -notmatch 'not found'){$d1}else{'NO_D1_LINE'})"
$d1all = Sh "logcat -d | grep -c 'D1 applyKeepScreenOn'"
L "D1_COUNT=$d1all"
A @('shell','screencap','-p','/sdcard/k_seg3.png') | Out-Null; A @('pull','/sdcard/k_seg3.png',"$OUT\k_seg3.png") | Out-Null

# ═════ 错误态三态（经代理读真实 DOM）═════════════
L "########## E-STATE 1/3: backend UNREACHABLE ##########"
A @('reverse','tcp:4095','tcp:4099') | Out-Null
Seed "opencode" "E1"
$pp1=ProxyPort; L "E1_PROXYPORT=$pp1"
$t1 = DomText $pp1
L "E1_DOM_TEXT: $t1"
L "E1_FRIENDLY=$($t1 -match '请检查服务器地址|检查地址与网络|重试|连不上|无法连接')"
L "E1_LEAKS_4095=$($t1 -match '4095') (expect False)"
$lE1 = Sh "logcat -d -s OcPerf OcWebHost OcWebHost/Proxy"; Add-Content -Path "$OUT\log2_E1.txt" -Value $lE1 -Encoding utf8
L "E1_probeOK=$($lE1 -match 'probe OK code=200') (expect False)  E1_AUTHFAILED=$(([regex]::Matches($lE1,'AUTH FAILED')).Count)"

A @('reverse','tcp:4095','tcp:4095') | Out-Null
L "########## E-STATE 2/3: WRONG password ##########"
Seed "wrongpass" "E2"
$pp2=ProxyPort; L "E2_PROXYPORT=$pp2"
$t2 = DomText $pp2
L "E2_DOM_TEXT: $t2"
L "E2_SAYS_AUTHFAIL=$($t2 -match '用户名或口令不对') (expect True)"
L "E2_ECHOES_PASSWORD=$($t2 -match 'wrongpass') (expect False)"
$lE2 = Sh "logcat -d -s OcPerf OcWebHost OcWebHost/Proxy"; Add-Content -Path "$OUT\log2_E2.txt" -Value $lE2 -Encoding utf8
L "E2_AUTHFAILED=$(([regex]::Matches($lE2,'AUTH FAILED')).Count) (expect >0)  E2_probeOK=$($lE2 -match 'probe OK code=200') (expect False)"

L "########## E-STATE 3/3: CORRECT password ##########"
Seed "opencode" "E3"
$pp3=ProxyPort; L "E3_PROXYPORT=$pp3"
$t3 = DomText $pp3
L "E3_DOM_TEXT: $t3"
L "E3_HAS_AUTH_DIALOG_TEXT=$($t3 -match '用户名或口令不对') (expect False)"
$lE3 = Sh "logcat -d -s OcPerf OcWebHost OcWebHost/Proxy"; Add-Content -Path "$OUT\log2_E3.txt" -Value $lE3 -Encoding utf8
$sid3=([regex]::Matches($lE3,'/api/session/(ses_[A-Za-z0-9]+)')|ForEach-Object{$_.Groups[1].Value}|Select-Object -Unique)
L "E3_SESSION_IDS_n=$($sid3.Count) -> $($sid3 -join ',')"
L "E3_AUTHFAILED=$(([regex]::Matches($lE3,'AUTH FAILED')).Count) (expect 0)"

# ═════ JS 桥 + 崩溃扫描 ═════
L "########## bridge / crash ##########"
$br = Sh "logcat -d | grep -iE 'OcWebHost|oc-host' | tail -30"
L "BRIDGE_HIT_LINES=$(if($br){($br -split '\|').Count}else{0})"
$cr = Sh "logcat -d | grep -iE 'FATAL|AndroidRuntime|RenderProcessGone|ClassNotFound|NoSuchMethod|NoClassDefFound' | head -15"
L "CRASH_LINES: $(if($cr){$cr}else{'NONE'})"
$noobf = Sh "logcat -d | grep -iE 'oc_debug_eval|Eval' | head -5"
L "DEBUG_EVAL_LINES(release应静默): $(if($noobf){$noobf}else{'NONE (expected in release)'})"
L "=== F11 probe2 done $(Get-Date -Format o) ==="
