# _f20_p3.ps1 — ② isSecureContext：诊断为什么回调没到（记全部请求 + 截图 + 前台 activity）
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$D="emulator-6450"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web"
$PORT=8099
$LOGF="$OUT\_f20_http_access.log"
if(Test-Path $LOGF){ Remove-Item $LOGF -Force }
function L { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; Add-Content -Path "$OUT\_f20_p3.log" -Value $s -Encoding utf8 }

$html = '<!doctype html><meta charset="utf-8"><body style="font:30px monospace"><div id=o>PENDING</div><script>var o="ISEC="+window.isSecureContext+" ORIGIN="+location.origin+" HOST="+location.host+" MEDIA="+!!navigator.mediaDevices+" GUM="+!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia);document.getElementById("o").textContent=o;try{fetch("/report?v="+encodeURIComponent(o))}catch(e){}</script></body>'

$job = Start-Job -ScriptBlock {
  param($port,$body,$logf)
  $l=[System.Net.HttpListener]::new(); $l.Prefixes.Add("http://127.0.0.1:$port/"); $l.Start(); $n=0
  while($l.IsListening -and $n -lt 40){
    try{
      $ctx=$l.GetContext(); $n++
      $q=$ctx.Request.QueryString['v']
      $line = "{0} | {1} {2} | v={3}" -f (Get-Date -Format 'HH:mm:ss'), $ctx.Request.HttpMethod, $ctx.Request.RawUrl, $q
      Add-Content -Path $logf -Value $line -Encoding utf8
      $b=[System.Text.Encoding]::UTF8.GetBytes($body)
      $ctx.Response.StatusCode=200; $ctx.Response.ContentType="text/html; charset=utf-8"
      $ctx.Response.ContentLength64=$b.Length
      $ctx.Response.OutputStream.Write($b,0,$b.Length); $ctx.Response.Close()
    }catch{ break }
  }
} -ArgumentList $PORT,$html,$LOGF

Start-Sleep -Seconds 2
L "listener=$($job.State)"
try { $r=Invoke-WebRequest "http://127.0.0.1:$PORT/" -UseBasicParsing -TimeoutSec 5; L "host self-check code=$($r.StatusCode) len=$($r.Content.Length)" } catch { L "selfcheck FAIL $($_.Exception.Message)" }
& $adb -s $D reverse tcp:$PORT tcp:$PORT | Out-Null

# 显式用 monkey 拉起（比 am start VIEW 稳），并记录前台是谁
L "--- monkey launch ---"
& $adb -s $D shell monkey -p com.android.chrome -c android.intent.category.LAUNCHER 1 2>&1 | ForEach-Object { L "  monkey: $_" }
Start-Sleep -Seconds 6
L ("foreground BEFORE url: " + ((& $adb -s $D shell dumpsys activity activities 2>&1 | Select-String -Pattern 'mResumedActivity|topResumedActivity' | Select-Object -First 2) -join ' ; '))
& $adb -s $D shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:$PORT/" 2>&1 | ForEach-Object { L "  amstart: $_" }
Start-Sleep -Seconds 10
L ("foreground AFTER url: " + ((& $adb -s $D shell dumpsys activity activities 2>&1 | Select-String -Pattern 'mResumedActivity|topResumedActivity' | Select-Object -First 2) -join ' ; '))
& $adb -s $D shell screencap -p /sdcard/_f20_p3.png | Out-Null
& $adb -s $D pull /sdcard/_f20_p3.png "$OUT\_f20_p3_chrome.png" 2>&1 | ForEach-Object { L "  pull: $_" }
& $adb -s $D shell uiautomator dump /sdcard/_f20_p3.xml | Out-Null
(& $adb -s $D shell cat /sdcard/_f20_p3.xml) -join '' | Out-File -Encoding utf8 "$OUT\_f20_p3_ui.xml"
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
& $adb -s $D reverse --remove tcp:$PORT | Out-Null
L "=== ACCESS LOG (原文，每条请求) ==="
if(Test-Path $LOGF){ Get-Content $LOGF | ForEach-Object { L "  $_" } } else { L "  (no access log file)" }
L "=== done ==="
