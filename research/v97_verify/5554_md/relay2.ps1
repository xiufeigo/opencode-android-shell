$ErrorActionPreference = 'Stop'
$dir = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
$log = Join-Path $dir 'relay_req.log'
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 4096)
$listener.Start()
"listening 4096 -> 4095 at $(Get-Date -Format o)" | Out-File $log -Encoding utf8

$deadline = (Get-Date).AddSeconds(600)
while ((Get-Date) -lt $deadline) {
    if (-not $listener.Pending()) { Start-Sleep -Milliseconds 150; continue }
    try {
        $client = $listener.AcceptTcpClient()
        $up = [System.Net.Sockets.TcpClient]::new('127.0.0.1', 4095)
        "--- CONN $(Get-Date -Format o) ---" | Out-File $log -Append -Encoding utf8
        $cs = $client.GetStream(); $us = $up.GetStream()
        $buf = New-Object byte[] 16384
        $client.ReceiveTimeout = 20000; $up.ReceiveTimeout = 20000
        # forward both directions until either side closes
        $t1 = [System.Threading.Tasks.Task]::Run([Action]{ while($true){ $n=$cs.Read($buf,0,$buf.Length); if($n -le 0){break}; [Text.Encoding]::ASCII.GetString($buf,0,$n) | Out-File $log -Append -Encoding utf8; $us.Write($buf,0,$n); $us.Flush() } })
        $t2 = [System.Threading.Tasks.Task]::Run([Action]{ $rb=New-Object byte[] 16384; while($true){ $n=$us.Read($rb,0,$rb.Length); if($n -le 0){break}; $cs.Write($rb,0,$n); $cs.Flush() } })
        [System.Threading.Tasks.Task]::WaitAny($t1,$t2) | Out-Null
        "--- END $(Get-Date -Format o) ---" | Out-File $log -Append -Encoding utf8
        $client.Close(); $up.Close()
    } catch { "ERR: $_" | Out-File $log -Append -Encoding utf8 }
}
$listener.Stop()
