# _f20_p5.ps1 — ② isSecureContext（在我们自己的 WebView 里读）+ ① ③ 会话/消息 API
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$D="emulator-6450"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web"
$Pkg='com.xiufeigo.opencodeshell.web.debug'
$Act='com.xiufeigo.opencodeshell.web.WebHostActivity'
function L { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; Add-Content -Path "$OUT\_f20_p5.log" -Value $s -Encoding utf8 }

# WebHostActivity.kt:82 EXTRA_DEBUG_EVAL="oc_debug_eval"；:857 仅 BuildConfig.DEBUG 生效；
# :862-863 结果打进 logcat tag OcWebHost/Eval
$js = "'ISEC='+window.isSecureContext+' ORIGIN='+location.origin+' HOST='+location.host+' PROTO='+location.protocol+' MEDIA='+!!navigator.mediaDevices+' GUM='+!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia)+' BARCODE='+(typeof BarcodeDetector!=='undefined')"

L "=== inject debug eval into OUR WebView ==="
& $adb -s $D shell am force-stop $Pkg | Out-Null
Start-Sleep -Seconds 1
& $adb -s $D logcat -c | Out-Null
& $adb -s $D shell am start -n "$Pkg/$Act" --es oc_debug_eval "$js" 2>&1 | ForEach-Object { L "  amstart: $_" }
Start-Sleep -Seconds 14
$ev = ((& $adb -s $D logcat -d -s OcWebHost/Eval:V 2>&1) -join "`n")
L "=== OcWebHost/Eval 原文 ==="
if($ev.Trim()){ $ev -split "`n" | ForEach-Object { L "  $_" } } else { L "  (EMPTY - eval 通道没出结果)" }
$ev | Out-File -Encoding utf8 "$OUT\_f20_eval_isec.txt"

# 代理端口 + 前置断言
$lc = ((& $adb -s $D logcat -d -s OcWebHost:V OcPerf:V 2>&1) -join "`n")
$lc | Out-File -Encoding utf8 "$OUT\_f20_p5.logcat.txt"
$pp = ($lc | Select-String -Pattern 'listening on http://127\.0\.0\.1:(\d+)' | Select-Object -Last 1)
if($pp -match '127\.0\.0\.1:(\d+)'){ $P=$Matches[1] } else { $P="40167" }
L "=== PRECONDITION (前置断言) ==="
$lc -split "`n" | Where-Object { $_ -match 'probe OK|probe before|passwordPresent|proxyListening|loadUrlIssued' } | Select-Object -First 8 | ForEach-Object { L "  $_" }
L "PROXY_PORT=$P"

L "=== ①/③ API 经回环代理（Basic 由代理注入）==="
foreach($ep in '/api/info','/api/session','/api/config','/api/app','/api/agent'){
  $r = ((& $adb -s $D shell "curl -s -o /dev/null -w 'code=%{http_code} type=%{content_type} size=%{size_download}' http://127.0.0.1:$P$ep" 2>&1) -join '')
  L "  proxy$ep -> $r"
}
L "=== 同一路径直连上游(无凭据)对照 ==="
foreach($ep in '/api/info','/api/session','/auth/probe'){
  $r = ((& $adb -s $D shell "curl -s -o /dev/null -w 'code=%{http_code} type=%{content_type} size=%{size_download}' http://127.0.0.1:4095$ep" 2>&1) -join '')
  L "  direct$ep -> $r"
}
$sd = ((& $adb -s $D shell "curl -s http://127.0.0.1:$P/api/session" 2>&1) -join '')
$sd | Out-File -Encoding utf8 "$OUT\_f20_api_session.txt"
L "  /api/session body(300) = " + $sd.Substring(0,[Math]::Min(300,$sd.Length))
if($sd -match '"id"\s*:\s*"([^"]+)"'){
  $sid=$Matches[1]; L "  first session id=$sid"
  $m = ((& $adb -s $D shell "curl -s -o /dev/null -w 'code=%{http_code} type=%{content_type} size=%{size_download}' 'http://127.0.0.1:$P/api/session/$sid/message'" 2>&1) -join '')
  L "  MESSAGE /api/session/$sid/message -> $m"
  $mb = ((& $adb -s $D shell "curl -s 'http://127.0.0.1:$P/api/session/$sid/message'" 2>&1) -join '')
  $mb | Out-File -Encoding utf8 "$OUT\_f20_api_message.txt"
  L "  message body(200) = " + $mb.Substring(0,[Math]::Min(200,$mb.Length))
} else { L "  no session id parsed" }
L "=== 代理日志里的 auth 相关行 ==="
$lc -split "`n" | Where-Object { $_ -match 'auth rejected|401|403|no password' } | Select-Object -First 10 | ForEach-Object { L "  $_" }
L "=== F20 P5 done ==="
