# _f20_isec.ps1 — ② 核 window.isSecureContext（回环来源 127.0.0.1:<port>）
# WebView 未开 CDP（源码无 setWebContentsDebuggingEnabled）=> 改用同一 Chromium 内核的
# 设备 Chrome 打开一个**回环来源**的探针页，读出真值。
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$D="emulator-6450"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web"
$PORT=8099
function L { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; Add-Content -Path "$OUT\_f20_isec.log" -Value $s -Encoding utf8 }

$html = @'
<!doctype html><html><head><meta charset="utf-8"><title>isec</title>
<style>body{font:28px monospace;background:#fff;color:#000;margin:8px}</style></head>
<body>
<div id="o">PENDING</div>
<script>
var media = !!(navigator.mediaDevices);
var gum   = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
var bd    = (typeof BarcodeDetector !== 'undefined');
var o = "ISEC=" + window.isSecureContext
     + " ORIGIN=" + location.origin
     + " HOST=" + location.host
     + " PROTO=" + location.protocol
     + " MEDIA=" + media
     + " GUM=" + gum
     + " BARCODE=" + bd;
document.getElementById('o').textContent = o;
document.title = o;
console.log(o);
</script></body></html>
'@

# --- 起一个只服务这一个文件的回环 HTTP 监听（后台 job）---
$job = Start-Job -ScriptBlock {
  param($port,$body)
  $l = [System.Net.HttpListener]::new()
  $l.Prefixes.Add("http://127.0.0.1:$port/")
  $l.Start()
  while ($l.IsListening) {
    try {
      $ctx = $l.GetContext()
      $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
      $ctx.Response.StatusCode = 200
      $ctx.Response.ContentType = "text/html; charset=utf-8"
      $ctx.Response.ContentLength64 = $bytes.Length
      $ctx.Response.OutputStream.Write($bytes,0,$bytes.Length)
      $ctx.Response.Close()
    } catch { break }
  }
} -ArgumentList $PORT,$html

Start-Sleep -Seconds 2
L "listener job state=$($job.State)"
try {
  $r = Invoke-WebRequest -Uri "http://127.0.0.1:$PORT/" -UseBasicParsing -TimeoutSec 5
  L "host self-check: code=$($r.StatusCode) len=$($r.Content.Length)"
} catch { L "host self-check FAILED: $($_.Exception.Message)" }

& $adb -s $D reverse tcp:$PORT tcp:$PORT | Out-Null
L ("reverse list: " + ((& $adb -s $D reverse --list) -join ' | '))

& $adb -s $D shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:$PORT/" 2>&1 | ForEach-Object { L "am start: $_" }
Start-Sleep -Seconds 9

& $adb -s $D shell uiautomator dump /sdcard/_f20_isec.xml | Out-Null
$xml = (& $adb -s $D shell cat /sdcard/_f20_isec.xml) -join ''
$xml | Out-File -Encoding utf8 "$OUT\_f20_isec_ui.xml"
L "=== extracted text/desc from UI dump ==="
foreach($m in [regex]::Matches($xml,'<node[^>]*')){
  $n=$m.Value
  $t=''; $c=''
  if($n -match 'text="([^"]+)"'){ $t=$Matches[1] }
  if($n -match 'content-desc="([^"]+)"'){ $c=$Matches[1] }
  if($t -match 'ISEC|ORIGIN|MEDIA|GUM|BARCODE|PENDING'){ L "  TEXT: $t" }
  if($c -match 'ISEC|ORIGIN|MEDIA|GUM|BARCODE|PENDING'){ L "  DESC: $c" }
}

# 兜底：直接从页面 URL 侧再取一次（有些 Chrome 版本把结果只进 title）
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
L "listener job stopped"
& $adb -s $D reverse --remove tcp:$PORT | Out-Null
L "reverse removed"
L "=== F20 isec done ==="
