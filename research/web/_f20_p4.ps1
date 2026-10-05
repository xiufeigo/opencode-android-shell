# _f20_p4.ps1 — ② isSecureContext 真值 + ① ③ 会话/消息 API（一次跑完）
$ErrorActionPreference='Continue'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$D="emulator-6450"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\web"
$PORT=8099
$LOGF="$OUT\_f20_http_access.log"
if(Test-Path $LOGF){ Remove-Item $LOGF -Force }
function L { param($m) $s="[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'),$m; Write-Output $s; Add-Content -Path "$OUT\_f20_p4.log" -Value $s -Encoding utf8 }
function UI { (& $adb -s $D shell uiautomator dump /sdcard/_f20p4.xml 2>$null | Out-Null); ((& $adb -s $D shell cat /sdcard/_f20p4.xml) -join '') }
function FindB { param($X,$Pat) foreach($m in [regex]::Matches($X,'<node[^>]*')){ $n=$m.Value; if($n -match $Pat -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){ return @([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) } } return $null }
function Tap { param($b,[int]$ms=700) A2 $b; Start-Sleep -Milliseconds $ms }
function A2 { param($b) & $adb -s $D shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null }

# ---------- 步骤 1：清掉 Chrome 首次运行 ----------
L "=== step1: dismiss Chrome first run ==="
& $adb -s $D shell am force-stop com.android.chrome | Out-Null
& $adb -s $D shell monkey -p com.android.chrome -c android.intent.category.LAUNCHER 1 > $null 2>&1
Start-Sleep -Seconds 5
for($i=1;$i -le 6;$i++){
  $x = UI
  $fg = ((& $adb -s $D shell dumpsys activity activities 2>&1 | Select-String -Pattern 'topResumedActivity' | Select-Object -First 1) -join '')
  L "  iter$i fg=$($fg.Trim())"
  if($fg -notmatch 'FirstRun'){ L "  first run cleared"; break }
  $b = FindB $x 'class="android\.widget\.Button"'
  if(-not $b){ $b = FindB $x 'clickable="true"' }
  if($b){ L "  tap [$($b[0]),$($b[1])][$($b[2]),$($b[3])]"; Tap $b 1500 } else { L "  no tappable node -> pressing BACK"; & $adb -s $D shell input keyevent KEYCODE_BACK | Out-Null; Start-Sleep -Seconds 2 }
}
$fg = ((& $adb -s $D shell dumpsys activity activities 2>&1 | Select-String -Pattern 'topResumedActivity' | Select-Object -First 1) -join '')
L "  fg now = $($fg.Trim())"

# ---------- 步骤 2：起监听 + 探针页 ----------
$html = '<!doctype html><meta charset="utf-8"><body style="font:28px monospace;background:#fff"><div id=o>PENDING</div><script>var o="ISEC="+window.isSecureContext+" ORIGIN="+location.origin+" HOST="+location.host+" MEDIA="+!!navigator.mediaDevices+" GUM="+!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia);document.getElementById("o").textContent=o;try{fetch("/report?v="+encodeURIComponent(o))}catch(e){}</script></body>'
$job = Start-Job -ScriptBlock {
  param($port,$body,$logf)
  $l=[System.Net.HttpListener]::new(); $l.Prefixes.Add("http://127.0.0.1:$port/"); $l.Start(); $n=0
  while($l.IsListening -and $n -lt 40){
    try{
      $ctx=$l.GetContext(); $n++
      Add-Content -Path $logf -Value ("{0} | {1} {2} | v={3}" -f (Get-Date -Format 'HH:mm:ss'),$ctx.Request.HttpMethod,$ctx.Request.RawUrl,$ctx.Request.QueryString['v']) -Encoding utf8
      $b=[System.Text.Encoding]::UTF8.GetBytes($body)
      $ctx.Response.StatusCode=200; $ctx.Response.ContentType="text/html; charset=utf-8"
      $ctx.Response.ContentLength64=$b.Length
      $ctx.Response.OutputStream.Write($b,0,$b.Length); $ctx.Response.Close()
    }catch{ break }
  }
} -ArgumentList $PORT,$html,$LOGF
Start-Sleep -Seconds 2
& $adb -s $D reverse tcp:$PORT tcp:$PORT | Out-Null
L "=== step2: load probe page ==="
& $adb -s $D shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:$PORT/" | Out-Null
Start-Sleep -Seconds 9
L "=== ACCESS LOG (原文) ==="
if(Test-Path $LOGF){ Get-Content $LOGF | ForEach-Object { L "  $_" } } else { L "  (no access log)" }
& $adb -s $D shell screencap -p /sdcard/_f20_p4.png | Out-Null
& $adb -s $D pull /sdcard/_f20_p4.png "$OUT\_f20_p4_probe.png" > $null 2>&1
L "screenshot pulled"
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
& $adb -s $D reverse --remove tcp:$PORT | Out-Null

# ---------- 步骤 3：① 第三证 会话/消息 API ----------
L "=== step3: session/message API through loopback proxy ==="
$pp = (& $adb -s $D logcat -d -s OcWebHost:V 2>&1 | Select-String -Pattern 'listening on http://127\.0\.0\.1:(\d+)' | Select-Object -Last 1)
if($pp -match '127\.0\.0\.1:(\d+)'){ $P=$Matches[1] } else { $P="40167" }
L "PROXY_PORT=$P"
foreach($ep in '/api/session','/api/config','/api/app','/api/agent'){
  $r = ((& $adb -s $D shell "curl -s -o /dev/null -w 'code=%{http_code} type=%{content_type} size=%{size_download}' http://127.0.0.1:$P$ep" 2>&1) -join '')
  L "  proxy$ep -> $r"
}
$b = ((& $adb -s $D shell "curl -s http://127.0.0.1:$P/api/session" 2>&1) -join '')
L "  /api/session body(400) = " + $b.Substring(0,[Math]::Min(400,$b.Length))
$b | Out-File -Encoding utf8 "$OUT\_f20_api_session.txt"
# 消息 API：列某会话消息
$sd = ((& $adb -s $D shell "curl -s http://127.0.0.1:$P/api/session" 2>&1) -join '')
if($sd -match '"id"\s*:\s*"([^"]+)"'){
  $sid=$Matches[1]; L "  first session id = $sid"
  $m = ((& $adb -s $D shell "curl -s -o /dev/null -w 'code=%{http_code} type=%{content_type} size=%{size_download}' 'http://127.0.0.1:$P/api/session/$sid/message'" 2>&1) -join '')
  L "  /api/session/$sid/message -> $m"
} else { L "  no session id parsed" }
L "=== F20 P4 done ==="
