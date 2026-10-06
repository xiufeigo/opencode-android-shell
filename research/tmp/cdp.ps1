param([Parameter(Mandatory=$true)][string]$Expr, [int]$Port = 9222)

$ErrorActionPreference = 'Stop'
$targets = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json" -TimeoutSec 10
$page = $targets | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
if (-not $page) { throw "no page target" }

$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, [Threading.CancellationToken]::None).GetAwaiter().GetResult()

function Send-Cdp($obj) {
  $json = $obj | ConvertTo-Json -Compress -Depth 12
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
  $seg = [ArraySegment[byte]]::new($bytes)
  $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
}

function Read-Cdp() {
  $ms = New-Object System.IO.MemoryStream
  $buf = New-Object byte[] 65536
  do {
    $seg = [ArraySegment[byte]]::new($buf)
    $r = $ws.ReceiveAsync($seg, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    $ms.Write($buf, 0, $r.Count)
  } while (-not $r.EndOfMessage)
  [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
}

Send-Cdp @{ id = 1; method = 'Runtime.evaluate'; params = @{ expression = $Expr; returnByValue = $true; awaitPromise = $true } }
$deadline = (Get-Date).AddSeconds(30)
do {
  $raw = Read-Cdp
  $msg = $raw | ConvertFrom-Json
} while ($msg.id -ne 1 -and (Get-Date) -lt $deadline)

$ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'bye', [Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null

if ($msg.result.exceptionDetails) {
  Write-Output ("EXCEPTION: " + ($msg.result.exceptionDetails | ConvertTo-Json -Compress -Depth 6))
} else {
  $v = $msg.result.result.value
  if ($null -eq $v) { Write-Output "NULL" } else { Write-Output ($v | ConvertTo-Json -Compress -Depth 10) }
}
