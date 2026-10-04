# E5: 通过 WebView DevTools (CDP) 在 emulator-5636 上执行 JS / 下发 CDP 方法。
# 通道：web app 是 debuggable 构建 => WebView 自动开 webview_devtools_remote_<pid> unix socket。
#
# 用法 A（纯 JS 读数）:
#   .\cdp_run.ps1 -Js 'innerWidth'
# 用法 B（先改视口再读数，用于绕开设备上卡死的 freeform 窗口）:
#   .\cdp_run.ps1 -SetupJson '[{"method":"Emulation.setDeviceMetricsOverride","params":{"width":411,"height":914,"deviceScaleFactor":2.625,"mobile":true}}]' -Js 'innerWidth'
#   .\cdp_run.ps1 -ClearSetup   # 清除视口覆写，回到真实窗口尺寸
param(
  [string]$Js,
  [string]$JsFile,
  [string]$SetupJson,
  [switch]$ClearSetup,
  [int]$Port = 9222,
  [int]$TimeoutSec = 25
)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = 'emulator-5636'
$pkg = 'com.xiufeigo.opencodeshell.web.debug'
if (-not $Js -and -not $JsFile -and -not $SetupJson -and -not $ClearSetup) { Write-Error "need -Js/-JsFile/-SetupJson/-ClearSetup"; exit 1 }

$appPid = ((& $adb -s $serial shell pidof $pkg) | Out-String).Trim()
if (-not $appPid) { Write-Output "ERR_NO_PID"; exit 1 }
$unix = (& $adb -s $serial shell "cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1" | Out-String).Trim()
if (-not $unix) { Write-Output "ERR_NO_DEVTOOLS_SOCKET"; exit 1 }
& $adb -s $serial forward --remove "tcp:$Port" 2>&1 | Out-Null
& $adb -s $serial forward "tcp:$Port" "localabstract:$unix" 2>&1 | Out-Null

$list = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json/list" -TimeoutSec 10
$target = $list | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl } | Select-Object -First 1
if (-not $target) { Write-Output "ERR_NO_PAGE_TARGET"; exit 1 }
Write-Output ("# target: title=" + $target.title + " url=" + $target.url)

$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSec))
$ws.ConnectAsync([Uri]$target.webSocketDebuggerUrl, $cts.Token).GetAwaiter().GetResult() | Out-Null

$script:nextId = 0
function Send-Cdp($method, $params) {
  $script:nextId++
  $id = $script:nextId
  $obj = @{ id = $id; method = $method; params = $params }
  $p = $obj | ConvertTo-Json -Depth 12 -Compress
  $b = [System.Text.Encoding]::UTF8.GetBytes($p)
  $ws.SendAsync([ArraySegment[byte]]::new($b), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult() | Out-Null
  return $id
}
function Read-Id($wantId) {
  $sb = [System.Text.StringBuilder]::new()
  $buf = New-Object byte[] 65536
  while ($true) {
    $res = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $cts.Token).GetAwaiter().GetResult()
    if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { return $null }
    [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count))
    try { $j = ($sb.ToString() | ConvertFrom-Json) } catch { continue }
    if ($j.id -eq $wantId) { return $j }
  }
}

# 1) 视口覆写
if ($SetupJson) {
  foreach ($c in ($SetupJson | ConvertFrom-Json)) {
    $id = Send-Cdp $c.method $c.params
    $r = Read-Id $id
    Write-Output ("# setup " + $c.method + " -> " + ($(if ($r.error) { "ERR " + ($r.error | ConvertTo-Json -Compress) } else { "ok" })))
  }
  Start-Sleep -Milliseconds 700
}
if ($ClearSetup) {
  $id = Send-Cdp 'Emulation.clearDeviceMetricsOverride' @{}
  $null = Read-Id $id
  Write-Output "# cleared device metrics override"
  Start-Sleep -Milliseconds 700
}

# 2) 可选：读数
if ($Js -or $JsFile) {
  $expr = if ($JsFile) { Get-Content $JsFile -Raw } else { $Js }
  $id = Send-Cdp 'Runtime.evaluate' @{ expression = $expr; returnByValue = $true; awaitPromise = $true }
  $r = Read-Id $id
  if (-not $r) { Write-Output "ERR_NO_REPLY" }
  elseif ($r.result.exceptionDetails) { Write-Output ("EXCEPTION: " + ($r.result.exceptionDetails | ConvertTo-Json -Depth 8 -Compress)) }
  else {
    $v = $r.result.result.value
    if ($v -is [string]) { Write-Output $v } else { Write-Output ($v | ConvertTo-Json -Depth 20) }
  }
}
$ws.Dispose()
