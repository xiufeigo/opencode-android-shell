$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $m; Add-Content -Path "$OUT\p2.log" -Value $s -Encoding utf8; Write-Output $s }

# --- corrected: device md5 with argv form (no shell quoting) ---
$apkPath = (Sh "pm path $PKG") -replace '.*package:',''
L "DEVICE_MD5_ARGV: $(A @('shell','md5sum',$apkPath))"

# curl availability on device (decides how we read WebView-only text)
$curlV = Sh "curl --version"
L "CURL_PRESENT=$($curlV -match 'curl')  raw=$curlV"

function UiDump($tag) {
  A @('shell','uiautomator','dump','/sdcard/u.xml') | Out-Null
  $d = Sh "cat /sdcard/u.xml"
  Add-Content -Path "$OUT\ui_$tag.xml" -Value $d -Encoding utf8
  A @('shell','screencap','-p',"/sdcard/$tag.png") | Out-Null
  A @('pull',"/sdcard/$tag.png","$OUT\$tag.png") | Out-Null
  return $d
}
function Seed($pw,$tag) {
  A @('shell','pm','clear',$PKG) | Out-Null
  A @('logcat','-c') | Out-Null
  A @('shell','am','start','-n',$ACT) | Out-Null
  Start-Sleep -Seconds 7
  $d = Sh "cat /sdcard/d.xml"
  A @('shell','uiautomator','dump','/sdcard/d.xml') | Out-Null
  $d = Sh "cat /sdcard/d.xml"
  $et = [regex]::Matches($d,'class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  L "  seed[$tag]: dialog=$(if($d -match '配置'){'YES'}else{'NO'}) editTexts=$($et.Count)"
  if ($et.Count -ge 1) {
    $cx=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2)
    $cy=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
    Sh "input tap $cx $cy" | Out-Null
    Start-Sleep -Milliseconds 1500
    foreach ($ch in $pw.ToCharArray()) { Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 250 }
  }
  A @('shell','uiautomator','dump','/sdcard/d2.xml') | Out-Null
  $d2 = Sh "cat /sdcard/d2.xml"
  $dots = [regex]::Match($d2,'text="([•*]+)[^"]*"')
  L "  seed[$tag]: maskedInput='$($dots.Groups[1].Value)' len=$($dots.Groups[1].Value.Length)"
  # FIX: uiautomator mangles last glyph -> match prefix 保存并连 (not 保存并连接)
  $b = [regex]::Match($d2,'text="(保存并连[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $b.Success) { $b = [regex]::Match($d2,'<node[^>]*text="保存并连[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') }
  if ($b.Success) {
    $bx=[int](([int]$b.Groups[2].Value+[int]$b.Groups[4].Value)/2)
    $by=[int](([int]$b.Groups[3].Value+[int]$b.Groups[5].Value)/2)
    Sh "input tap $bx $by" | Out-Null
    L "  seed[$tag]: tapped SAVE '$($b.Groups[1].Value)' ($bx,$by)"
  } else {
    $b2 = [regex]::Match($d2,'<node[^>]*text="保存并连[^"]*"[^>]*bounds="(\[[^"]*\])"')
    L "  seed[$tag]: SAVE_MATCH_FAIL node=$($b2.Value)"
  }
  Start-Sleep -Seconds 12
}

# =========== CASE A: correct password -> official UI, NO auth dialog ===========
L "=== CASE A: correct password ==="
Seed "opencode" "A"
$logA = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_A_correct.txt" -Value $logA -Encoding utf8
L "A_probeOK=$($logA -match 'probe OK code=200')  A_AUTH_FAILED=$(([regex]::Matches($logA,'AUTH FAILED')).Count)"
$g = ([regex]::Matches($logA,'GET http://127\.0\.0\.1:4095/api/[^ ]*') | ForEach-Object { $_.Value } | Select-Object -Unique)
L "A_API_GETS_UNIQUE=$($g.Count)"; foreach($x in $g){ L "  A_API $x" }
foreach($k in 'J.g1.preflightOk','J.g1.blockedOldWebview','M.g1.firstContent','probe OK'){
  $m=[regex]::Match($logA,"(?m)^.*$([regex]::Escape($k)).*$")
  L "A_LINE[$k]: $(if($m.Success){$m.Value.Trim()}else{'ABSENT'})"
}
$uiA = UiDump "A_correct_ui"
L "A_HAS_AUTH_DIALOG=$($uiA -match '用户名或口令不对')  (expect False)"
L "A_UI_LEN=$($uiA.Length)"
$wt = ([regex]::Matches($uiA,'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' -and $_ -notmatch '^=+$' -and $_ -notmatch 'resource-id' } | Select-Object -Unique)
L "A_UI_VISIBLE_TEXTS: $($wt -join ' | ')"
L "A_UI_HAS_OFFICIAL_MARKER=$($uiA -match 'session|会话|新会话|Session|project|Project')"

# =========== first-screen 3 runs ===========
L "=== first-screen 3 cold runs ==="
$times=@()
for ($i=1; $i -le 3; $i++) {
  A @('shell','am','force-stop',$PKG) | Out-Null
  Start-Sleep -Seconds 3
  A @('logcat','-c') | Out-Null
  A @('shell','am','start','-n',$ACT) | Out-Null
  Start-Sleep -Seconds 15
  $lg = Sh "logcat -d -s OcPerf OcPerfJS"
  Add-Content -Path "$OUT\log_run$i.txt" -Value $lg -Encoding utf8
  $m = [regex]::Match($lg,'M\.g1\.firstContent[^\r\n]*?t=(\d+)ms')
  $pf= [regex]::Match($lg,'J\.g1\.preflightOk[^\r\n]*?t=(\d+)ms')
  if ($m.Success) { $times += [int]$m.Groups[1].Value; L "RUN${i}_FIRSTCONTENT_MS=$($m.Groups[1].Value)  line=[$($m.Value.Trim())]" } else { L "RUN${i}_FIRSTCONTENT=NOT_FOUND" }
  L "RUN${i}_PREFLIGHT_OK=$($pf.Success) $(if($pf.Success){$pf.Groups[1].Value})  blockedOldWebview=$(([regex]::Matches($lg,'blockedOldWebview')).Count)"
  A @('shell','screencap','-p',"/sdcard/run$i.png") | Out-Null
  A @('pull',"/sdcard/run$i.png","$OUT\run$i.png") | Out-Null
}
if ($times.Count -ge 1) { $s=$times|Sort-Object; $med = if($s.Count%2){$s[[int]($s.Count/2)]}else{[int](($s[$s.Count/2-1]+$s[$s.Count/2])/2)}; L "FIRSTSCREEN_ALL_MS=$($times -join ',')  MEDIAN=${med}  MIN=$($s[0])  MAX=$($s[-1])  n=$($times.Count)" }
L "=== P2 DONE ==="
