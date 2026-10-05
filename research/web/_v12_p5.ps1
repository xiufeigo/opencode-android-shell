$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p5.log" -Value $s -Encoding utf8; Write-Output $s }
function KeepState($label) {
  $w = Sh "dumpsys window windows"
  $p = Sh "dumpsys power"
  $attrs=[regex]::Match($w,'(?ms)Window\{[^}]*com\.xiufeigo\.opencodeshell[^}]*\}.*?(?=\r?\n\s*Window\{|\z)')
  $fs='UNKNOWN'
  if($attrs.Success){ $m2=[regex]::Match($attrs.Value,'flags=([0-9xXa-fA-F]+)')
    if($m2.Success){ $fl=$m2.Groups[1].Value; $v=[Convert]::ToInt64($fl,16); $fs=if(($v -band 0x80) -ne 0){'true'}else{'false'}
      L "${label}_flags=0x${fl} flagSet(0x80)=${fs}" } }
  $hits=([regex]::Matches($w,'KEEP_SCREEN_ON')).Count
  $mw=[regex]::Match($p,'mWakefulness=(\w+)')
  $wsl=[regex]::Matches($p,'(?m)^.*(?:KEEP_SCREEN_ON|mHoldingDisplaySuspendBlocker).*$')
  $ts=Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
  L "${label}_WALLCLOCK=$ts  window_KEEP_SCREEN_ON_hits=$hits  power_mWakefulness=$($mw.Groups[1].Value)"
  foreach($x in $wsl){ L "${label}_power_raw: $($x.Value.Trim())" }
  $raw=([regex]::Matches($w,'(?m)^.*KEEP_SCREEN_ON.*$')|ForEach-Object{$_.Value.Trim()})
  foreach($x in $raw){ L "${label}_window_raw: $x" }
  return $fs
}

L "########## P5 keep-screen-on 3 phases + IME ##########"
# seed correct password first (E2 left the app on the wrong-password dialog)
function FindSaveBtn($xml) {
  $c=@()
  foreach ($m in [regex]::Matches($xml,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
    if ($y1 -ge 870 -and $x1 -ge 600) { $c += [pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2)} }
  }
  if($c.Count -eq 0){return $null}; return ($c | Sort-Object x1 -Descending | Select-Object -First 1)
}
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
L "SEED correct password for P5"
A @('shell','pm','clear',$PKG) | Out-Null
A @('logcat','-c') | Out-Null
A @('shell','am','start','-n',$ACT) | Out-Null
Start-Sleep -Seconds 7
A @('shell','uiautomator','dump','/sdcard/d.xml') | Out-Null
$d = Sh "cat /sdcard/d.xml"
$et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*password="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if ($et.Count -ge 1) {
  $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2); $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
  Sh "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 1400
  foreach ($ch in 'opencode'.ToCharArray()) { Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 260 }
}
A @('shell','uiautomator','dump','/sdcard/d2.xml') | Out-Null
$b = FindSaveBtn (Sh "cat /sdcard/d2.xml")
if ($b) { Sh "input tap $($b.cx) $($b.cy)" | Out-Null; L "SEED saved ($($b.cx),$($b.cy))" }
Start-Sleep -Seconds 14
$chk = Sh "logcat -d -s OcWebHost"
L "P5_SEED_probeOK=$($chk -match 'probe OK code=200')  firstContent=$($chk -match 'firstContent')"
$pp = [int]((Get-Content "$OUT\proxy_port.txt" -ErrorAction SilentlyContinue | Select-Object -Last 1) -replace '[^0-9]','')
L "PROXY_PORT=$pp"
$lg = Sh "logcat -d -s OcPerfJS OcWebHost"
$pm=[regex]::Match($lg,'http://127\.0\.0\.1:(\d{4,5})/api/')
if($pm.Success){ $pp=[int]$pm.Groups[1].Value; L "PROXY_PORT_DETECTED=$pp" }
$sid=([regex]::Matches($lg,'/api/session/(ses_[A-Za-z0-9]+)')|ForEach-Object{$_.Groups[1].Value}|Select-Object -First 1)
L "SESSION_ID=$sid"

L "PHASE0 baseline"
$b=KeepState "PHASE0"

# ---- PHASE 1: raise (real round start = POST to session message endpoint) ----
L "PHASE1 raise: POST to /api/session/<sid>/message (real round start)"
A @('logcat','-c') | Out-Null
if($pp -gt 0 -and $sid){
  $body = '{"parts":[{"type":"text","text":"v12 keep-screen-on probe"}]}'
  $code = Sh "curl -s -m 8 -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary '$body' http://127.0.0.1:$pp/api/session/$sid/message"
  L "PHASE1_post_http=$code"
}
Start-Sleep -Seconds 4
$l1=Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
$d1=([regex]::Matches($l1,'(?m)^.*D1 roundActive=.*$'))
foreach($x in $d1){ L "PHASE1_D1: $($x.Value.Trim())" }
$ks=([regex]::Matches($l1,'(?m)^.*applyKeepScreenOn.*$'))
foreach($x in $ks){ L "PHASE1_keepLog: $($x.Value.Trim())" }
$p1=KeepState "PHASE1_raised"

# ---- PHASE 2: normal round end releases ----
L "PHASE2 release: round ends (hold timeout / tracker idle). waiting 100s"
Start-Sleep -Seconds 100
$l2=Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
$d2=([regex]::Matches($l2,'(?m)^.*D1 roundActive=.*$'))
foreach($x in $d2){ L "PHASE2_D1: $($x.Value.Trim())" }
$bs=([regex]::Matches($l2,'(?m)^.*backstop.*$'))
foreach($x in $bs){ L "PHASE2_backstop: $($x.Value.Trim())" }
$p2=KeepState "PHASE2_afterEnd"

# ---- PHASE 3: +90s later still false ----
L "PHASE3: sleeping 95s to cover the +90s D1 backstop window"
Start-Sleep -Seconds 95
$l3=Sh "logcat -d -s OcWebHost OcWebHost/Proxy"
$bs3=([regex]::Matches($l3,'(?m)^.*backstop.*$'))
foreach($x in $bs3){ L "PHASE3_backstop: $($x.Value.Trim())" }
$p3=KeepState "PHASE3_tplus95s"
L "SUMMARY flagSet: PHASE0=$b PHASE1_raised=$p1 PHASE2_afterEnd=$p2 PHASE3_tplus95s=$p3"

# ---- IME overlap ----
L "########## IME overlap ##########"
$ins = Sh "dumpsys window"
$ms=[regex]::Match($ins,'mInputShown=(\w+)')
L "IME_mInputShown=$($ms.Groups[1].Value)"
# show the IME: tap the official UI's message box
A @('shell','uiautomator','dump','/sdcard/i.xml') | Out-Null
$d=Sh "cat /sdcard/i.xml"
$ed=([regex]::Matches($d,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'))
L "IME_clickable_nodes=$($ed.Count)"
if($ed.Count -ge 1){
  $n=($ed | Select-Object -Last 1)
  $cx=[int](([int]$n.Groups[1].Value+[int]$n.Groups[3].Value)/2); $cy=[int](([int]$n.Groups[2].Value+[int]$n.Groups[4].Value)/2)
  Sh "input tap $cx $cy" | Out-Null; L "IME_tapped=($cx,$cy)"
}
Start-Sleep -Seconds 3
$ins2 = Sh "dumpsys window"
$ms2=[regex]::Match($ins2,'mInputShown=(\w+)')
L "IME_mInputShown_after_tap=$($ms2.Groups[1].Value)"
$lines=([regex]::Matches($ins2,'(?m)^.*type=ime.*$'))
foreach($x in $lines){ L "IME_type_ime_line: $($x.Value.Trim())" }
$frames=([regex]::Matches($ins2,'(?m)^.*frame=.*$'))
foreach($x in $frames){ L "IME_frame_line: $($x.Value.Trim())" }
# compute overlap between the app window frame and the ime frame
$aw=[regex]::Match($ins2,'(?ms)Window\{[^}]*com\.xiufeigo\.opencodeshell[^}]*\}.*?(?=\r?\n\s*Window\{|\z)')
if($aw.Success){
  $fr=[regex]::Match($aw.Value,'frame=\[?([\d,\[\]]+)\]?')
  if($fr.Success){
    $n=$fr.Groups[1].Value -split '[,\[\]]+' | Where-Object {$_ -ne ''} | ForEach-Object {[int]$_}
    L "IME_appWindow_frame=($($n[0]),$($n[1]))-($($n[2]),$($n[3]))  appH=$($n[3]-$n[1])"
    $imeLine=($lines | Select-Object -First 1)
    if($imeLine){
      $im=[regex]::Match($imeLine.Value,'frame=\[?([\d,\[\]]+)\]?')
      if($im.Success){
        $k=$im.Groups[1].Value -split '[,\[\]]+' | Where-Object {$_ -ne ''} | ForEach-Object {[int]$_}
        L "IME_ime_frame=($($k[0]),$($k[1]))-($($k[2]),$($k[3]))  imeH=$($k[3]-$k[1])"
        $appBottom=$n[3]; $imeTop=$k[1]
        $ov=[Math]::Max(0, $appBottom - $imeTop)
        L "IME_OVERLAP_PX=$ov  (appBottom=$appBottom imeTop=$imeTop)"
        L "IME_MEASURE: $(if($ov -eq 0){'PASS overlap=0'}else{"FAIL overlap=${ov}px"})"
      } else { L "IME_ime_frame_parse=FAIL raw=$($imeLine.Value.Trim())" }
    } else { L "IME_type_ime_line=ABSENT" }
  } else { L "IME_appWindow_frame=PARSE_FAIL" }
} else { L "IME_appWindow=NOT_FOUND" }
$disp=[regex]::Match($ins2,'(?m)^.*init=.*$')
L "=== P5 DONE ==="
