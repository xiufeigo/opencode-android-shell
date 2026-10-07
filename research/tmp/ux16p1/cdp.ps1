param()
# UX16-P1 CDP helper. Page.crash = genuine renderer crash (root/kill blocked on this prod AVD).
$global:CDP = "http://127.0.0.1:9333"

function Get-PageWs {
    $l = (Invoke-WebRequest -Uri "$global:CDP/json/list" -UseBasicParsing -TimeoutSec 8).Content | ConvertFrom-Json
    $p = $l | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
    return @{ id = $p.id; ws = $p.webSocketDebuggerUrl; url = $p.url; title = $p.title }
}

# Send one CDP method on a fresh websocket; return decoded result field.
function Invoke-Cdp([string]$wsUrl, [string]$method, $params = $null, [int]$timeoutMs = 12000) {
    $ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $cts = [System.Threading.CancellationTokenSource]::new($timeoutMs)
    $ws.ConnectAsync([Uri]$wsUrl, $cts.Token).GetAwaiter().GetResult()
    $msg = @{ id = 1; method = $method } ; if ($params) { $msg.params = $params }
    $json = ($msg | ConvertTo-Json -Depth 12 -Compress)
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $seg = [ArraySegment[byte]]::new($bytes)
    $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult()
    $buf = New-Object byte[] 262144
    $sb = New-Object System.Text.StringBuilder
    do {
        $r = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $cts.Token).GetAwaiter().GetResult()
        [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf, 0, $r.Count))
    } while (-not $r.EndOfMessage)
    $ws.Dispose()
    $raw = $sb.ToString()
    # keep only the frame carrying our id
    foreach ($line in ($raw -split "`n")) {
        $t = $line.Trim()
        if ($t.Length -lt 2) { continue }
        try { $o = $t | ConvertFrom-Json } catch { continue }
        if ($o.id -eq 1) { return $o }
    }
    return @{ raw = $raw.Substring(0, [Math]::Min(600, $raw.Length)) }
}

function Js([string]$expr) {
    $pg = Get-PageWs
    $r = Invoke-Cdp $pg.ws "Runtime.evaluate" @{ expression = $expr; returnByValue = $true; awaitPromise = $true } 20000
    if ($r.result -and $r.result.result) { return $r.result.result.value }
    return "CDP_ERR: $($r | ConvertTo-Json -Depth 6 -Compress)"
}