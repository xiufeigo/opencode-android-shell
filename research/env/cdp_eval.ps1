# E5: 通过 WebView DevTools (CDP) 在 emulator-5636 上执行 JS。
# 通道来源：web app 是 debuggable 构建 ⇒ WebView 自动开 webview_devtools_remote_<pid> unix socket。
# 用法: .\cdp_eval.ps1 -Js '<js expression>' [-Port 9222] [-TimeoutSec 15]
# 返回值是 Runtime.evaluate 的 result（awaitPromise + returnByValue）。
param(
  [string]$Js,
  [string]$JsFile,
  [int]$Port = 9222,
  [int]$TimeoutSec = 20
)
if (-not $Js -and -not $JsFile) { Write-Error "need -Js or -JsFile"; exit 1 }
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = 'emulator-5636'
$pkg = 'com.xiufeigo.opencodeshell.web.debug'

# 1) 应用 pid
$appPid = ((& $adb -s $serial shell pidof $pkg) | Out-String).Trim()
if (-not $appPid) { Write-Output "ERR_NO_PID"; exit 1 }

# 2) devtools socket
$unix = (& $adb -s $serial shell "cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1" | Out-String).Trim()
if (-not $unix) { Write-Output "ERR_NO_DEVTOOLS_SOCKET"; exit 1 }

# 3) forward
& $adb -s $serial forward --remove "tcp:$Port" 2>&1 | Out-Null
& $adb -s $serial forward "tcp:$Port" "localabstract:$unix" 2>&1 | Out-Null

# 4) 找 page target
$list = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json/list" -TimeoutSec 10
$target = $list | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl } | Select-Object -First 1
if (-not $target) {
  Write-Output ("ERR_NO_PAGE_TARGET: " + (($list | ForEach-Object { $_.type + ' ' + $_.url }) -join ' | '))
  exit 1
}
$wsUrl = $target.webSocketDebuggerUrl
Write-Output ("# target: id=" + $target.id + " title=" + $target.title + " url=" + $target.url)

# 5) WebSocket Runtime.evaluate
$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSec))
$ws.ConnectAsync([Uri]$wsUrl, $cts.Token).GetAwaiter().GetResult()

$expr = $Js
if ($JsFile) { $expr = Get-Content $JsFile -Raw }
$payload = @{
  id = 1
  method = 'Runtime.evaluate'
  params = @{ expression = $expr; returnByValue = $true; awaitPromise = $true }
} | ConvertTo-Json -Depth 8 -Compress

$bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
$seg = [ArraySegment[byte]]::new($bytes)
$ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult()

# 读帧直到拿到 id=1 的响应
$sb = [System.Text.StringBuilder]::new()
$buf = New-Object byte[] 65536
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
  try {
    $rseg = [ArraySegment[byte]]::new($buf)
    $res = $ws.ReceiveAsync($rseg, $cts.Token).GetAwaiter().GetResult()
  } catch { break }
  if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { break }
  [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count))
  $txt = $sb.ToString()
  if ($txt -match '"id":1') {
    $j = $txt | ConvertFrom-Json
    if ($j.id -eq 1) {
      if ($j.result.exceptionDetails) {
        Write-Output ("EXCEPTION: " + ($j.result.exceptionDetails | ConvertTo-Json -Depth 6 -Compress))
      } else {
        $v = $j.result.result.value
        if ($v -is [string]) { Write-Output $v } else { Write-Output ($v | ConvertTo-Json -Depth 12) }
      }
      $ws.Dispose(); exit 0
    }
  }
}
$ws.Dispose()
Write-Output "ERR_TIMEOUT_OR_NO_ID1"
