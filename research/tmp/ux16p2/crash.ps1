# UX16-P2 CDP 真崩复验：连上 WebView 的 devtools socket，发一次 Page.crash，然后立刻断开。
# Page.crash 是协议级的渲染进程真崩溃（与 kill -9 渲染进程等价）——
# 本 AVD 是 production 镜像无 root，kill -9 会被系统拒绝（见批 #9 记录）。
param([int]$Port = 9333, [int]$TimeoutSec = 20)

$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-6790"

$mainPid = (& $adb -s $dev shell "pidof com.xiufeigo.opencodeshell.web.debug").Trim()
if (-not $mainPid) { throw "app not running" }
Write-Host "mainPid=$mainPid"

& $adb -s $dev forward "tcp:$Port" "localabstract:webview_devtools_remote_$mainPid" | Out-Null
Start-Sleep -Milliseconds 800

$pages = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json" -TimeoutSec 10
$target = $pages | Where-Object { $_.webSocketDebuggerUrl } | Select-Object -First 1
if (-not $target) { throw "no devtools target" }
Write-Host "target.url=$($target.url)"
Write-Host "target.title=$($target.title)"

$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSec))
$ws.ConnectAsync([Uri]$target.webSocketDebuggerUrl, $cts.Token).GetAwaiter().GetResult()
Write-Host "ws connected"

$msg = '{"id":1,"method":"Page.crash","params":{}}'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
$seg = [ArraySegment[byte]]::new($bytes)
$ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult()
Write-Host "crash issued @ $(Get-Date -Format 'HH:mm:ss.fff')"

# 收一条回执就够（预期是 Inspector.detached）
try {
  $buf = New-Object byte[] 8192
  $r = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $cts.Token).GetAwaiter().GetResult()
  $txt = [System.Text.Encoding]::UTF8.GetString($buf, 0, $r.Count)
  Write-Host "recv=$txt"
} catch { Write-Host "recv=<none: $($_.Exception.Message)>" }

# 立刻断开：排除"是挂着的 CDP 连接把渲染进程拖死"这个替代解释
$ws.Dispose()
Write-Host "ws disposed @ $(Get-Date -Format 'HH:mm:ss.fff')"
