$ErrorActionPreference='Continue'
# F11 — R8 版设备验证：与非 R8 版**同机同轮**对比
# 前提：R8 APK 已构建成功。顺序：先装非R8(基线)量一轮 → 再装R8量一轮 → 装R8做常亮/错误态
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6290"
$PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$NOR8="C:\sensorsdata\main\program\opencode-android-shell\research\deliver\opencode-shell-web-release-nor8.apk"
$R8="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f11\opencode-shell-web-release-r8.apk"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web\out_f11"
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function Sh($cmd){ $o = & $adb -s $S shell $cmd 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m; Add-Content -Path "$OUT\dev.log" -Value $s -Encoding utf8; Write-Output $s }

# ── F5 教训：input text 会丢字符 → 逐字符输入
function FindSaveBtn($xml) {
  $c=@()
  foreach ($m in [regex]::Matches($xml,'<node[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
    $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
    if ($y1 -ge 870 -and $x1 -ge 600) { $c += [pscustomobject]@{cx=[int](($x1+$x2)/2);cy=[int](($y1+$y2)/2);x1=$x1} }
  }
  if($c.Count -eq 0){return $null}; return ($c | Sort-Object x1 -Descending | Select-Object -First 1)
}
function VisText($xml){
  $vals=@()
  foreach($m in [regex]::Matches(($xml|Out-String),'text="([^"]+)"')){ $v=$m.Groups[1].Value; if($v -ne ''){ $vals += $v } }
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
  L "  seed[$tag]: dialogPresent=$(if($d -match '保存并连接'){'YES'}else{'NO'}) editTextCount=$($et.Count)"
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
function UiDump($tag) {
  A @('shell','uiautomator','dump','/sdcard/u.xml') | Out-Null
  $d = Sh "cat /sdcard/u.xml"
  Add-Content -Path "$OUT\ui_$tag.xml" -Value $d -Encoding utf8
  A @('shell','screencap','-p',"/sdcard/$tag.png") | Out-Null
  A @('pull',"/sdcard/$tag.png","$OUT\$tag.png") | Out-Null
  return $d
}
function ProxyPort() {
  $lg = Sh "logcat -d -s OcPerfJS OcWebHost"
  $m=[regex]::Match($lg,'http://127\.0\.0\.1:(\d{4,5})/api/')
  if($m.Success){return [int]$m.Groups[1].Value}; return -1
}
function FirstScreen($tag) {
  $times=@()
  for($i=1;$i -le 3;$i++){
    A @('shell','am','force-stop',$PKG) | Out-Null; Start-Sleep -Seconds 3
    A @('logcat','-c') | Out-Null
    A @('shell','am','start','-n',$ACT) | Out-Null
    Start-Sleep -Seconds 16
    $lg=Sh "logcat -d -s OcPerf OcPerfJS"; Add-Content -Path "$OUT\log_${tag}_run$i.txt" -Value $lg -Encoding utf8
    $m=[regex]::Match($lg,'M\.g1\.firstContent[^\r\n]*?t=(\d+)ms')
    if($m.Success){ $times+=[int]$m.Groups[1].Value; L "${tag}_RUN${i}_FIRSTCONTENT_MS=$($m.Groups[1].Value)" } else { L "${tag}_RUN${i}_FIRSTCONTENT=NOT_FOUND" }
    L "${tag}_RUN${i}_blockedOldWebview=$(([regex]::Matches($lg,'blockedOldWebview')).Count)"
  }
  if($times.Count -ge 1){ $s=$times|Sort-Object; $med=if($s.Count%2){$s[[int]($s.Count/2)]}else{[int](($s[$s.Count/2-1]+$s[$s.Count/2])/2)}; L "${tag}_FIRSTSCREEN_ALL_MS=$($times -join ',') MEDIAN=${med} MIN=$($s[0]) MAX=$($s[-1]) n=$($times.Count)"; return $med }
  return -1
}

# ═════════════════ 0. 环境/指纹 ═════════════════
L "=== F11 device verify start $(Get-Date -Format o) ==="
L "SERIAL=$S  AVD=OC_Deliver_R2"
L "FINGERPRINT: $(Sh 'getprop ro.build.fingerprint')"
L "MODEL: $(Sh 'getprop ro.product.model') / $(Sh 'getprop ro.build.version.release') / SDK $(Sh 'getprop ro.build.version.sdk') / ABI $(Sh 'getprop ro.product.cpu.abi')"
L "GEOM: $(Sh 'wm size')  DENSITY: $(Sh 'wm density')"
L "WEBVIEW: $(Sh 'dumpsys package com.google.android.webview | grep versionName | head -1')"
L "R8_APK_EXISTS=$((Test-Path $R8))"
if (Test-Path $R8) { L "R8_LOCAL_MD5=$((Get-FileHash $R8 -Algorithm MD5).Hash) R8_LOCAL_SIZE=$((Get-Item $R8).Length)" }
L "NOR8_LOCAL_MD5=$((Get-FileHash $NOR8 -Algorithm MD5).Hash) NOR8_LOCAL_SIZE=$((Get-Item $NOR8).Length)"
L "REVERSE: $(A @('reverse','tcp:4095','tcp:4095'))"
L "HOST_4095: $((Get-NetTCPConnection -LocalPort 4095 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { "LISTEN pid=$($_.OwningProcess)" }) -join ',')"

# ═════════════════ 1. 非 R8 基线（**同机同轮**）═════════════════
L "########## BASELINE non-R8 (same device, same round) ##########"
L "INSTALL_NOR8: $(A @('install','-r','-g',$NOR8))"
$apkPath = (Sh "pm path $PKG") -replace '.*package:',''
L "NOR8_DEVICE_PATH=$apkPath"
if ($apkPath) { L "NOR8_DEVICE_MD5=$(Sh "md5sum $apkPath")" }
Seed "opencode" "NOR8"
$lN = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_NOR8_correct.txt" -Value $lN -Encoding utf8
L "NOR8_AUTHFAILED=$(([regex]::Matches($lN,'AUTH FAILED')).Count)  NOR8_probeOK=$($lN -match 'probe OK code=200')"
$sidN=([regex]::Matches($lN,'/api/session/(ses_[A-Za-z0-9]+)')|ForEach-Object{$_.Groups[1].Value}|Select-Object -Unique)
L "NOR8_SESSION_IDS_n=$($sidN.Count) -> $($sidN -join ',')"
$uN = UiDump "NOR8_official"
L "NOR8_HAS_AUTH_DIALOG=$($uN -match '用户名或口令不对') (expect False)"
L "NOR8_VISIBLE: $(VisText $uN)"
$medNOR8 = FirstScreen "NOR8"

# ═════════════════ 2. 装 R8 版 ═════════════════
L "########## INSTALL R8 ##########"
L "INSTALL_R8: $(A @('install','-r','-g',$R8))"
$apkPath2 = (Sh "pm path $PKG") -replace '.*package:',''
L "R8_DEVICE_PATH=$apkPath2"
if ($apkPath2) { L "R8_DEVICE_MD5=$(Sh "md5sum $apkPath2")" }
L "PKG: $(Sh "dumpsys package $PKG | grep versionName | head -1")"

# ═════════════════ 3. R8 前置断言：已鉴权 + 数据已到 ═════════════════
L "########## R8 CASE A: correct password -> authenticated + data arrived ##########"
Seed "opencode" "R8_A"
$lA = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_R8_A_correct.txt" -Value $lA -Encoding utf8
L "R8A_AUTHFAILED=$(([regex]::Matches($lA,'AUTH FAILED')).Count) (expect 0)"
L "R8A_probeOK=$($lA -match 'probe OK code=200')"
$getsA=([regex]::Matches($lA,'GET http://127\.0\.0\.1:4095/api/[^ ]*')|ForEach-Object{$_.Value}|Select-Object -Unique)
L "R8A_API_GETS_UNIQUE=$($getsA.Count)"
foreach($g in $getsA){ L "  R8A_API_GET $g" }
$sidA=([regex]::Matches($lA,'/api/session/(ses_[A-Za-z0-9]+)')|ForEach-Object{$_.Groups[1].Value}|Select-Object -Unique)
L "R8A_SESSION_IDS_n=$($sidA.Count) -> $($sidA -join ',')"
L "R8A_firstContent=$($lA -match 'M\.g1\.firstContent')"
$fcA=[regex]::Match($lA,'(?m)^.*M\.g1\.firstContent.*$'); L "R8A_FIRSTCONTENT: $(if($fcA.Success){$fcA.Value.Trim()}else{'ABSENT'})"
$pfA=[regex]::Match($lA,'(?m)^.*J\.g1\.preflightOk.*$'); L "R8A_PREFLIGHT: $(if($pfA.Success){$pfA.Value.Trim()}else{'ABSENT'})"
L "R8A_blockedOldWebview=$(([regex]::Matches($lA,'blockedOldWebview')).Count)"
$uA = UiDump "R8_A_official"
L "R8A_HAS_AUTH_DIALOG=$($uA -match '用户名或口令不对') (expect False)"
L "R8A_VISIBLE: $(VisText $uA)"
# 骨架屏 vs 真实会话列表
L "R8A_SKELETON=$($uA -match 'skeleton|骨架|shimmer')"
$ppA=ProxyPort; L "R8A_PROXYPORT=$ppA"
if($ppA -gt 0){ $h=Sh "curl -s -m 5 http://127.0.0.1:$ppA/"; L "R8A_SERVED_len=$($h.Length) has_title=$($h -match '<title>')" }
$medR8 = FirstScreen "R8"

# ═════════════════ 4. 口令存储（Tink 静默降级检测）═════════════════
L "########## R8 password persistence (Tink) ##########"
A @('shell','am','force-stop',$PKG) | Out-Null
Start-Sleep -Seconds 4
A @('logcat','-c') | Out-Null
A @('shell','am','start','-n',$ACT) | Out-Null
Start-Sleep -Seconds 16
$uB = UiDump "R8_B_relaunch"
L "R8B_AFTER_RELAUNCH_HAS_AUTH_DIALOG=$($uB -match '用户名或口令不对|保存并连接') (expect False => secret survived)"
L "R8B_VISIBLE: $(VisText $uB)"
$lB = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost"
Add-Content -Path "$OUT\log_R8_B_relaunch.txt" -Value $lB -Encoding utf8
L "R8B_probeOK=$($lB -match 'probe OK code=200')"
L "R8B_AUTHFAILED=$(([regex]::Matches($lB,'AUTH FAILED')).Count)"
# 设备端确实存在加密 prefs 文件？
L "R8B_PREFS_FILES: $(Sh "run-as $PKG ls -l /data/data/$PKG/shared_prefs 2>/dev/null || echo NO_RUNAS")"
$lB2 = Sh "logcat -d | grep -i 'EncryptedSharedPreferences unavailable' | head -3"
L "R8B_TINK_SILENT_FALLBACK_HITS=$(([regex]::Matches(($lB2|Out-String),'unavailable')).Count) (expect 0)"

# ═════════════════ 5. 常亮三段（含 +90s）═════════════════
L "########## R8 keep-screen-on 3 segments ##########"
function KeepState($label) {
  $w = Sh "dumpsys window windows"
  $attrs=[regex]::Match($w,'(?ms)Window\{[^}]*com\.xiufeigo\.opencodeshell[^}]*\}.*?(?=\r?\n\s*Window\{|\z)')
  $fs='UNKNOWN'
  if($attrs.Success){
    $m2=[regex]::Match($attrs.Value,'flags=([0-9xXa-fA-F]+)')
    if($m2.Success){ $v=[Convert]::ToInt64($m2.Groups[1].Value,16); $fs=if(($v -band 0x80) -ne 0){'true'}else{'false'} }
  }
  $hits=([regex]::Matches($w,'KEEP_SCREEN_ON')).Count
  $pw2=Sh "dumpsys power"
  $mw=[regex]::Match($pw2,'mWakefulness=(\w+)')
  L "${label} FLAG_0x80_SET=$fs KEEP_SCREEN_ON_hits=$hits mWakefulness=$($mw.Groups[1].Value) ts=$(Get-Date -Format 'HH:mm:ss')"
  return $fs
}
$k1 = KeepState "R8_KSEG1_t0"
Start-Sleep -Seconds 10
$k2 = KeepState "R8_KSEG2_t10s"
Start-Sleep -Seconds 20
$k3 = KeepState "R8_KSEG3_t30s"
Start-Sleep -Seconds 60
$k4 = KeepState "R8_KSEG4_t+90s"
L "R8_KEEPSEGMENTS: t0=$k1 t10=$k2 t30=$k3 t90=$k4 (expect all true)"

# ═════════════════ 6. 错误态三态 ═════════════════
L "########## R8 error tri-state ##########"
# E1 unreachable
L "REVERSE_WRONG: $(A @('reverse','tcp:4095','tcp:4099'))"
Seed "opencode" "R8_E1"
$lE1 = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_R8_E1_unreachable.txt" -Value $lE1 -Encoding utf8
$uE1 = UiDump "R8_E1_unreachable"
L "R8E1_VISIBLE: $(VisText $uE1)"
L "R8E1_FRIENDLY=$($uE1 -match '请检查服务器地址|检查地址与网络|重试|连不上')"
L "R8E1_LEAKS_4095=$($uE1 -match '4095') (expect False)"
L "REVERSE_FIX: $(A @('reverse','tcp:4095','tcp:4095'))"
# E2 wrong password
Seed "wrongpass" "R8_E2"
$lE2 = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_R8_E2_wrongpw.txt" -Value $lE2 -Encoding utf8
$uE2 = UiDump "R8_E2_wrongpw"
L "R8E2_VISIBLE: $(VisText $uE2)"
L "R8E2_SAYS_AUTHFAIL=$($uE2 -match '用户名或口令不对') (expect True)"
L "R8E2_ECHOES_PASSWORD=$($uE2 -match 'wrongpass') (expect False)"
L "R8E2_AUTHFAILED_HITS=$(([regex]::Matches($lE2,'AUTH FAILED')).Count)"
# E3 correct again (恢复)
Seed "opencode" "R8_E3"
$lE3 = Sh "logcat -d -s OcPerf OcPerfJS OcWebHost OcWebHost/Proxy"
Add-Content -Path "$OUT\log_R8_E3_correct.txt" -Value $lE3 -Encoding utf8
$uE3 = UiDump "R8_E3_official"
L "R8E3_VISIBLE: $(VisText $uE3)"
L "R8E3_HAS_AUTH_DIALOG=$($uE3 -match '用户名或口令不对') (expect False)"
$sidE3=([regex]::Matches($lE3,'/api/session/(ses_[A-Za-z0-9]+)')|ForEach-Object{$_.Groups[1].Value}|Select-Object -Unique)
L "R8E3_SESSION_IDS_n=$($sidE3.Count)"

# ═════════════════ 7. JS 桥直连（debug eval 通道在 release 关闭 ⇒ 走 UI/bridge 证据）═════════════════
L "########## R8 JS bridge evidence ##########"
$lBridge = Sh "logcat -d | grep -iE 'OcWebHost|oc-host|addJavascriptInterface|RoundActivityTracker|KeepScreenOn' | tail -40"
Add-Content -Path "$OUT\log_R8_bridge.txt" -Value $lBridge -Encoding utf8
L "R8_BRIDGE_LOG_PRESENT=$($lBridge.Length -gt 0) len=$($lBridge.Length)"
L "R8_BRIDGE_SNIPPET: $((($lBridge|Out-String) -replace '\s+',' ').Trim().Substring(0,[Math]::Min(1200,(($lBridge|Out-String) -replace '\s+',' ').Trim().Length)))"

# ═════════════════ 8. 崩溃检查 ═════════════════
L "########## R8 crash scan ##########"
$cr = Sh "logcat -d | grep -iE 'FATAL EXCEPTION|AndroidRuntime.*com.xiufeigo|RenderProcessGone|ClassNotFoundException|NoSuchMethodError|NoClassDefFoundError' | head -20"
L "R8_CRASH_LINES: $(if($cr){$cr}else{'NONE'})"

L "FIRSTSCREEN_COMPARISON: NOR8_MEDIAN=$medNOR8 ms | R8_MEDIAN=$medR8 ms"
L "=== F11 device verify done $(Get-Date -Format o) ==="
