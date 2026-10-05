# _f20_p2.ps1 — ② isSecureContext 硬证据（页面自报）+ ① ③ 会话/消息 API
# 上一版靠 uiautomator 抓文本失败（WebView 文本未进 a11y 树）=> 改成让页面
# 自己 fetch 回调宿主监听端口，把真值回传，落盘为原文。
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$D="emulator-6450"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web"
$PORT=8099
$RPT="$OUT\_f20_isec_report.txt"
if(Test-Path $RPT){ Remove-Item $RPT -Force }
function L { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; Add-Content -Path "$OUT\_f20_p2.log" -Value $s -Encoding utf8 }

$html = @'
<!doctype html><html><head><meta charset="utf-8"><title>isec</title>
<style>body{font:30px monospace;background:#fff;margin:8px}</style></head>
<body><div id="o">PENDING</div>
<script>
var o = "ISEC=" + window.isSecureContext
     + " ORIGIN=" + location.origin
     + " HOST=" + location.host
     + " MEDIA=" + !!navigator.mediaDevices
     + " GUM=" + !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
     + " BARCODE=" + (typeof BarcodeDetector !== 'undefined')
     + " UA=" + navigator.userAgent;
document.getElementById('o').textContent = o;
try { fetch("/report?v=" + encodeURIComponent(o)); } catch(e) {}
</script></body></html>
'@

$job = Start-Job -ScriptBlock {
  param($port,$body,$rpt)
  $l = [System.Net.HttpListener]::new()
  $l.Prefixes.Add("http://127.0.0.1:$port/")
  $l.Start()
  $n = 0
  while ($l.IsListening -and $n -lt 25) {
    try {
      $ctx = $l.GetContext(); $n++
      $q = $ctx.Request.QueryString['v']
      if ($q) {
        $line = "REPORT #{0}: {1}" -f $n, [System.Uri]::UnescapeDataString($q)
        Add-Content -Path $rpt -Value $line -Encoding utf8
      }
      $b = [System.Text.Encoding]::UTF8.GetBytes($body)
      $ctx.Response.StatusCode=200; $ctx.Response.ContentType="text/html; charset=utf-8"
      $ctx.Response.ContentLength64=$b.Length
      $ctx.Response.OutputStream.Write($b,0,$b.Length); $ctx.Response.Close()
    } catch { break }
  }
} -ArgumentList $PORT,$html,$RPT

Start-Sleep -Seconds 2
L "listener job=$($job.State)"
& $adb -s $D reverse tcp:$PORT tcp:$PORT | Out-Null
L ("reverse: " + ((& $adb -s $D reverse --list) -join ' | '))
& $adb -s $D shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:$PORT/" | Out-Null
Start-Sleep -Seconds 10
L "=== PAGE SELF-REPORT (原文) ==="
if(Test-Path $RPT){ Get-Content $RPT | ForEach-Object { L "  $_" } } else { L "  !! NO REPORT RECEIVED (页面没回调 => 未能取到原文)" }
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
& $adb -s $D reverse --remove tcp:$PORT | Out-Null
L "listener stopped; reverse removed"

# ---------- ① 第三证：会话/消息 API 经回环代理 ----------
$pp = (& $adb -s $D logcat -d -s OcWebHost:V 2>&1 | Select-String -Pattern 'listening on http://127\.0\.0\.1:(\d+)' | Select-Object -Last 1)
if($pp -match '127\.0\.0\.1:(\d+)'){ $P=$Matches[1] } else { $P="40167" }
L "=== PROXY_PORT=$P ==="
foreach($ep in '/api/session','/api/session/','/api/config','/api/app','/api/directory'){
  $r = (& $adb -s $D shell "curl -s -o /dev/null -w 'code=%{http_code} type=%{content_type} size=%{size_download}' http://127.0.0.1:$P$ep" 2>&1) -join ''
  L "  proxy$ep -> $r"
}
L "=== /api/session body (前 400 字节) ==="
$b = (& $adb -s $D shell "curl -s http://127.0.0.1:$P/api/session" 2>&1) -join ''
L ("  " + $b.Substring(0,[Math]::Min(400,$b.Length)))
$b | Out-File -Encoding utf8 "$OUT\_f20_api_session.txt"
L "=== F20 P2 done ==="
