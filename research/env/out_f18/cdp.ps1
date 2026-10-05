param(
  [Parameter(Mandatory=$true)][string]$Serial,
  [Parameter(Mandatory=$true)][string]$Js,
  [int]$TimeoutSec = 25
)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# 1) 找 devtools socket
$unix = & $adb -s $Serial shell cat /proc/net/unix
$line = ($unix | Select-String 'webview_devtools_remote' | Select-Object -First 1).Line
if (-not $line) { Write-Output "CDP_NO_SOCKET"; exit 2 }
$name = ($line -split '@')[-1].Trim()
$pid_ = ($line -split '\s+')[-1]
$sockPath = "/dev/socket/$($name -replace 'webview_devtools_remote_','webview_devtools_remote_')"

# 2) forward
& $adb -s $Serial forward --remove tcp:9333 2>&1 | Out-Null
& $adb -s $Serial forward tcp:9333 "localabstract:$name" | Out-Null

# 3) 拿 ws url
$targets = Invoke-RestMethod -Uri "http://127.0.0.1:9333/json/list" -TimeoutSec 10
$page = $targets | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl } | Select-Object -First 1
if (-not $page) { Write-Output "CDP_NO_PAGE"; exit 3 }
$ws = $page.webSocketDebuggerUrl

# 4) websocket client
$client = [System.Net.WebSockets.ClientWebSocket]::new()
$cts = [System.Threading.CancellationTokenSource]::new($TimeoutSec * 1000)
$client.ConnectAsync([Uri]$ws, $cts.Token).GetAwaiter().GetResult() | Out-Null

function Send-Cdp($obj) {
  $json = ($obj | ConvertTo-Json -Compress -Depth 8)
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  $seg = [ArraySegment[byte]]::new($bytes)
  $client.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult() | Out-Null
}
function Recv-Cdp() {
  $buf = New-Object byte[] 65536
  $ms = [System.IO.MemoryStream]::new()
  do {
    $seg = [ArraySegment[byte]]::new($buf)
    $r = $client.ReceiveAsync($seg, $cts.Token).GetAwaiter().GetResult()
    if ($r.Count -gt 0) { $ms.Write($buf, 0, $r.Count) }
  } while (-not $r.EndOfMessage)
  [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
}

Send-Cdp @{ id = 1; method = "Runtime.enable"; params = @{} } | Out-Null
$expr = $Js -replace "`r?`n",' '
$payload = @{ expression = $expr; returnByValue = $true; awaitPromise = $true }
Send-Cdp @{ id = 2; method = "Runtime.evaluate"; params = $payload } | Out-Null

$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
  $resp = Recv-Cdp
  if ($resp -match '^\s*\{"id":2\s*,') {
    $o = $resp | ConvertFrom-Json
    if ($o.result.exceptionDetails) {
      Write-Output ("EXC: " + ($o.result.exceptionDetails | ConvertTo-Json -Compress -Depth 6))
    } else {
      $v = $o.result.result.value
      if ($null -eq $v) { Write-Output ("RAW: " + $resp) }
      elseif ($v -is [string]) { Write-Output $v }
      else { Write-Output ($v | ConvertTo-Json -Compress -Depth 8) }
    }
    break
  }
}
$client.Dispose()
