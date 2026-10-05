$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6250"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$APK="C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web-release-nor8.apk"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_v12"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path "$OUT\p1.log" -Value $s -Encoding utf8; Write-Output $s }

# ---------- 0. install + identity ----------
L "=== P0 install / identity ==="
L "PRECHECK_MD5_LOCAL: $((Get-FileHash $APK -Algorithm MD5).Hash)  size=$((Get-Item $APK).Length)"
L "PKG_LIST: $(Sh 'pm list packages | grep opencode')"
L "INSTALL: $(A @('install','-r','-g',$APK))"
$apkPath = (Sh "pm path $PKG") -replace '.*package:',''
L "DEVICE_APK_PATH=$apkPath"
if ($apkPath) { L "DEVICE_MD5: $(Sh "md5sum $apkPath")" }
# device fingerprint
L "FINGERPRINT: $(Sh 'getprop ro.build.fingerprint')"
L "FINGERPRINT2: $(Sh 'getprop ro.product.model') / $(Sh 'getprop ro.build.version.release') / SDK $(Sh 'getprop ro.build.version.sdk') / ABI $(Sh 'getprop ro.product.cpu.abi')"
L "SERIAL=$S  AVD=OC_Deliver_R2  GEOM=$(Sh 'wm size')  DENSITY=$(Sh 'wm density')"
L "WEBVIEW: $(Sh 'dumpsys package com.google.android.webview | grep versionName | head -1')"

# ---------- reverse (Lead backend untouched) ----------
L "REVERSE_SET: $(A @('reverse','tcp:4095','tcp:4095'))"
L "REVERSE_LIST: $(A @('reverse','--list'))"
L "HOST_4095: $((Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { "LISTEN pid=$($_.OwningProcess)" }) -join ',')"

# ---------- CDP availability probe (empirical) ----------
$u = Sh "cat /proc/net/unix"
$socks = ([regex]::Matches($u,'webview_devtools_remote_\d+') | ForEach-Object { $_.Value } | Select-Object -Unique)
L "DEVTOOLS_SOCKETS=$($socks -join ',')  (empty => CDP unavailable on this build)"

# ---------- seed password helper ----------
function Seed($pw) {
  A @('shell','pm','clear',$PKG) | Out-Null
  A @('logcat','-c') | Out-Null
  A @('shell','am','start','-n',$ACT) | Out-Null
  Start-Sleep -Seconds 7
  A @('shell','uiautomator','dump','/sdcard/d.xml') | Out-Null
  $d = Sh "cat /sdcard/d.xml"
  $et = [regex]::Matches($d,'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  L "  seed: dialogPresent=$(if($d -match '保存并连接'){'YES'}else{'NO'}) editTextCount=$($et.Count)"
  if ($et.Count -ge 1) {
    $c1x=[int](([int]$et[0].Groups[1].Value+[int]$et[0].Groups[3].Value)/2)
    $c1y=[int](([int]$et[0].Groups[2].Value+[int]$et[0].Groups[4].Value)/2)
    Sh "input tap $c1x $c1y" | Out-Null
    Start-Sleep -Milliseconds 1500
    foreach ($ch in $pw.ToCharArray()) { Sh "input text $ch" | Out-Null; Start-Sleep -Milliseconds 250 }
    L "  seed: typed pw len=$($pw.Length) char-by-char (F5 fidelity rule)"
  }
  $b = [regex]::Match($d,'text="保存并连接"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if ($b.Success) {
    $bx=[int](([int]$b.Groups[1].Value+[int]$b.Groups[3].Value)/2)
    $by=[int](([int]$b.Groups[2].Value+[int]$b.Groups[4].Value)/2)
    Sh "input tap $bx $by" | Out-Null
    L "  seed: tapped save ($bx,$by)"
  } else { L "  seed: SAVE_BTN_NOT_FOUND" }
  Start-Sleep -Seconds 10
}

function LogDump($label) {
  $log = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
  Add-Content -Path "$OUT\log_$label.txt" -Value $log -Encoding utf8
  return $log
}
function UiText($tag) {
  A @('shell','uiautomator','dump','/sdcard/u.xml') | Out-Null
  $d = Sh "cat /sdcard/u.xml"
  Add-Content -Path "$OUT\ui_$tag.xml" -Value $d -Encoding utf8
  A @('shell','screencap','-p',"/sdcard/$tag.png") | Out-Null
  A @('pull',"/sdcard/$tag.png","$OUT\$tag.png") | Out-Null
  return $d
}

# ================= CASE A: correct password -> official UI, no dialog =================
L "=== CASE A: correct password 'opencode' ==="
Seed "opencode"
$logA = LogDump "A_correct"
L "A_AUTH_FAILED_count=$(([regex]::Matches($logA,'AUTH FAILED')).Count)"
L "A_probeOK=$($logA -match 'probe OK code=200')"
$gets = ([regex]::Matches($logA,'GET http://127\.0\.0\.1:4095/api/[^ ]*') | ForEach-Object { $_.Value } | Select-Object -Unique)
L "A_API_GETS_UNIQUE=$($gets.Count)"
foreach ($g in $gets) { L "  A_API_GET $g" }
L "A_firstContent=$($logA -match 'M\.g1\.firstContent')"
if ($logA -match 'M\.g1\.firstContent[^\r\n]*') { L "A_FIRSTCONTENT_LINE: $($Matches[0])" }
if ($logA -match 'J\.g1\.preflightOk[^\r\n]*') { L "A_PREFLIGHT_LINE: $($Matches[0])" }
L "A_blockedOldWebview_count=$(([regex]::Matches($logA,'blockedOldWebview')).Count)"
$uiA = UiText "A_correct_ui"
L "A_HAS_AUTH_DIALOG=$($uiA -match '用户名或口令不对')"
L "A_UI_LEN=$($uiA.Length)  A_UI_HAS_OFFICIAL=$($uiA -match 'session|会话|新会话|project')"
L "A_UI_SNIPPET: $(if($uiA.Length -gt 900){$uiA.Substring(0,900)}else{$uiA})"

# ================= first-screen >=3 runs (content marker) =================
L "=== first-screen 3 cold runs (M.g1.firstContent t=) ==="
$times=@()
for ($i=1; $i -le 3; $i++) {
  A @('shell','am','force-stop',$PKG) | Out-Null
  Start-Sleep -Seconds 3
  A @('logcat','-c') | Out-Null
  A @('shell','am','start','-n',$ACT) | Out-Null
  Start-Sleep -Seconds 14
  $lg = Sh "logcat -d -s OcPerf OcPerfJS"
  $m = [regex]::Match($lg,'M\.g1\.firstContent[^\r\n]*?t=(\d+)ms')
  $mm = [regex]::Match($lg,'J\.g1\.preflightOk[^\r\n]*?t=(\d+)ms')
  if ($m.Success) { $times += [int]$m.Groups[1].Value; L "RUN${i}_FIRSTCONTENT_MS=$($m.Groups[1].Value)  raw=[$($m.Value)]" }
  else { L "RUN${i}_FIRSTCONTENT=NOT_FOUND (log tail: $((($lg -split '\| ') | Select-Object -Last 3) -join ' || '))" }
  if ($mm.Success) { L "RUN${i}_PREFLIGHT_OK_MS=$($mm.Groups[1].Value)" }
  L "RUN${i}_blockedOldWebview=$(([regex]::Matches($lg,'blockedOldWebview')).Count)"
  A @('shell','screencap','-p',"/sdcard/run$i.png") | Out-Null
  A @('pull',"/sdcard/run$i.png","$OUT\run$i.png") | Out-Null
}
if ($times.Count -ge 1) {
  $s = $times | Sort-Object
  $med = if ($s.Count % 2 -eq 1) { $s[[int]($s.Count/2)] } else { [int](($s[$s.Count/2 -1] + $s[$s.Count/2])/2) }
  L "FIRSTSCREEN_ALL=$($times -join ',')  MEDIAN=${med}ms  MIN=$($s[0])ms  MAX=$($s[-1])ms"
}
L "=== P1 DONE ==="
