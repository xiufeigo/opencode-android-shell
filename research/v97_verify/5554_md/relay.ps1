$ErrorActionPreference = 'Stop'
# Raw TCP relay: listen 4096 -> forward to 4095, log the device->host request bytes.
$log = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\relay_req.log'
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 4096)
$listener.Start()
"listening 4096 -> 4095" | Out-File $log -Encoding utf8

$deadline = (Get-Date).AddSeconds(150)
while ((Get-Date) -lt $deadline) {
    if (-not $listener.Pending()) { Start-Sleep -Milliseconds 200; continue }
    $client = $listener.AcceptTcpClient()
    $up = [System.Net.Sockets.TcpClient]::new('127.0.0.1', 4095)
    $cs = $client.GetStream(); $us = $up.GetStream()
    $buf = New-Object byte[] 8192
    "--- CONN $(Get-Date -Format o) ---" | Out-File $log -Append -Encoding utf8
    $done = $false
    while (-not $done) {
        # device -> upstream
        $n = $cs.Read($buf, 0, $buf.Length)
        if ($n -le 0) { break }
        $txt = [Text.Encoding]::ASCII.GetString($buf, 0, $n)
        $txt | Out-File $log -Append -Encoding utf8
        $us.Write($buf, 0, $n); $us.Flush()
    }
    $client.Close(); $up.Close()
}
$listener.Stop()
