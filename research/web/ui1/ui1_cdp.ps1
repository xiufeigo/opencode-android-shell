# UI1 轮次自建 CDP 助手（只读轮：仅用于取数，不改 webapp/app 源码）
# 用法: .\ui1_cdp.ps1 -Js '<js>' [-Port 9231] [-Serial emulator-6690]
param(
  [string]$Js,
  [string]$JsFile,
  [int]$Port = 9231,
  [string]$Serial = 'emulator-6690',
  [int]$TimeoutSec = 20
)
if (-not $Js -and -not $JsFile) { Write-Error "need -Js or -JsFile"; exit 1 }
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = 'com.xiufeigo.opencodeshell.web.debug'

$appPid = ((& $adb -s $Serial shell pidof $pkg) | Out-String).Trim()
if (-not $appPid) { Write-Output "ERR_NO_PID"; exit 1 }
$unix = (& $adb -s $Serial shell "cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1" | Out-String).Trim()
if (-not $unix) { Write-Output "ERR_NO_DEVTOOLS_SOCKET"; exit 1 }
Write-Output ("# socket=" + $unix)

& $adb -s $Serial forward "tcp:$Port" "localabstract:$unix" 2>&1 | Out-Null
$list = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json/list" -TimeoutSec 10
$target = $list | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl } | Select-Object -First 1
if (-not $target) {
  Write-Output ("ERR_NO_PAGE_TARGET: " + (($list | ForEach-Object { $_.type + ' ' + $_.url }) -join ' | '))
  exit 1
}
$wsUrl = $target.webSocketDebuggerUrl

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

$sb = [System.Text.StringBuilder]::new()
$buf = New-Object byte[] 262144
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
