$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$d = 'emulator-6790'
$pkg = 'com.xiufeigo.opencodeshell.web.debug'

function Invoke-CdpEval([string]$expr) {
  $pidStr = (& $adb -s $d shell pidof $pkg).Trim()
  if (-not $pidStr) { return "NOPID" }
  $ErrorActionPreference = 'Continue'
  & $adb -s $d forward tcp:9222 "localabstract:webview_devtools_remote_$pidStr" 2>&1 | Out-Null
  $ErrorActionPreference = 'Stop'
  $list = Invoke-RestMethod -Uri 'http://127.0.0.1:9222/json/list' -TimeoutSec 10
  $page = $list | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl } | Select-Object -First 1
  if (-not $page) { return "NOPAGE" }
  $ws = New-Object System.Net.WebSockets.ClientWebSocket
  $ct = New-Object System.Threading.CancellationTokenSource
  $ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, $ct.Token).Wait(15000) | Out-Null
  $msg = '{"id":1,"method":"Runtime.evaluate","params":{"expression":' + (ConvertTo-Json $expr -Compress) + ',"returnByValue":true,"awaitPromise":true}}'
  $bytes = [Text.Encoding]::UTF8.GetBytes($msg)
  $ws.SendAsync((New-Object System.ArraySegment[byte] (,$bytes)), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct.Token).Wait(15000) | Out-Null
  $buf = New-Object byte[] 65536
  $res = $ws.ReceiveAsync((New-Object System.ArraySegment[byte] (,$buf)), $ct.Token).Result
  $text = [Text.Encoding]::UTF8.GetString($buf, 0, $res.Count)
  $ws.Dispose()
  return $text
}

# bring to foreground
& $adb -s $d shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" | Out-Null
Start-Sleep -Seconds 6

# re-run the P9 write-back path: this makes the ORIGINAL shim's static permission = "granted"
$r = Invoke-CdpEval 'window.Notification.requestPermission()'
Write-Output ("REPERM: " + $r)
Start-Sleep -Seconds 2
Write-Output ("STATE: " + (Invoke-CdpEval 'JSON.stringify({req:window.__ocProbe.req, perm:window.Notification.permission})'))

& $adb -s $d logcat -c
& $adb -s $d shell input tap 540 2140
Start-Sleep -Seconds 1
& $adb -s $d shell input text "ping4"
Start-Sleep -Milliseconds 800
& $adb -s $d shell input keyevent 66
Start-Sleep -Seconds 1
& $adb -s $d shell input keyevent 3
Write-Output "HOME pressed; waiting 25s"
Start-Sleep -Seconds 25

Write-Output ("PROBE_BG: " + (Invoke-CdpEval 'JSON.stringify({ctor: window.__ocProbe.ctor, req: window.__ocProbe.req, read: window.__ocProbe.read, titles: window.__ocProbe.titles, vis: document.visibilityState, focus: document.hasFocus()})'))
& $adb -s $d logcat -d -s OcWebHost 2>&1 | Select-String -Pattern 'P7 onPause|synthetic|round end' | Select-Object -Last 6 | ForEach-Object { Write-Output ("LOG: " + $_.Line) }
& $adb -s $d shell dumpsys notification --noredact 2>&1 | Select-String -Pattern 'android.title=|android.text=' | Select-Object -First 2 | ForEach-Object { Write-Output ("NOTIF: " + $_.Line.Trim()) }
