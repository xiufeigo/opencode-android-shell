$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$d = 'emulator-6790'
$pkg = 'com.xiufeigo.opencodeshell.web.debug'

$pidStr = (& $adb -s $d shell pidof $pkg).Trim()
Write-Output "app pid = $pidStr"
if (-not $pidStr) { Write-Output "NO PID"; exit 1 }

try { & $adb -s $d forward --remove tcp:9222 2>&1 | Out-Null } catch { }
$ErrorActionPreference = 'Continue'
& $adb -s $d forward tcp:9222 "localabstract:webview_devtools_remote_$pidStr" | Out-Null
$ErrorActionPreference = 'Stop'
Start-Sleep -Seconds 2

$list = Invoke-RestMethod -Uri 'http://127.0.0.1:9222/json/list' -TimeoutSec 10
$page = $list | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl } | Select-Object -First 1
if (-not $page) { Write-Output "NO PAGE TARGET"; $list | ForEach-Object { Write-Output ("  " + $_.type + " " + $_.url) }; exit 1 }
Write-Output "target url = $($page.url)"

$ws = New-Object System.Net.WebSockets.ClientWebSocket
$ct = New-Object System.Threading.CancellationTokenSource
$ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, $ct.Token).Wait(15000) | Out-Null
Write-Output "ws state = $($ws.State)"

$expr = 'JSON.stringify({vis: document.visibilityState, focus: document.hasFocus(), perm: (window.Notification && window.Notification.permission), hasSetter: typeof window.__ocSetBackground, ctor: window.__ocProbe ? window.__ocProbe.ctor : "n/a", keys: Object.keys(window).filter(k=>k.indexOf("__ocProbe")>=0)})'
$msg = '{"id":1,"method":"Runtime.evaluate","params":{"expression":' + (ConvertTo-Json $expr -Compress) + ',"returnByValue":true}}'
$bytes = [Text.Encoding]::UTF8.GetBytes($msg)
$seg = New-Object System.ArraySegment[byte] (,$bytes)
$ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct.Token).Wait(15000) | Out-Null

$buf = New-Object byte[] 65536
$rseg = New-Object System.ArraySegment[byte] (,$buf)
$res = $ws.ReceiveAsync($rseg, $ct.Token).Result
$text = [Text.Encoding]::UTF8.GetString($buf, 0, $res.Count)
Write-Output "EVAL_RESULT: $text"
$ws.Dispose()
