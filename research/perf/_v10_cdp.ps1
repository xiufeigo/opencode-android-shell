$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6210"; $PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function LiveSock { $u=A @('shell','cat','/proc/net/unix'); $m=[regex]::Matches($u,'webview_devtools_remote_\d+'); if($m.Count -gt 0){return $m[0].Value}; return $null }

"LOG_AFTER_SEED: $(A @('shell','logcat','-d','-s','OcWebHost','OcWebHost/Proxy'))"
$sock = LiveSock
"LIVE_SOCK=$sock"
A @('forward','--remove-all') | Out-Null
"FWD: $(A @('forward','tcp:9222',"localabstract:$sock"))"
$FWDLIST = A @('forward','--list')
"FWD_LIST=$FWDLIST"
try { $j = Invoke-RestMethod -Uri 'http://127.0.0.1:9222/json' -TimeoutSec 8; "JSON_OK pages=$($j.Count)"; $j | ForEach-Object { "  type=$($_.type) title=$($_.title) url=$($_.url)" } } catch { "JSON_FAIL: $($_.Exception.Message)" }
$pg = $j | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
if ($pg) {
  "WSURL=$($pg.webSocketDebuggerUrl)"
  try {
    $ws=[System.Net.WebSockets.ClientWebSocket]::new()
    $cts=[Threading.CancellationTokenSource]::new(10000)
    $ws.ConnectAsync([Uri]$pg.webSocketDebuggerUrl,$cts.Token).GetAwaiter().GetResult()
    "WS_CONNECTED=$($ws.State)"
    $expr='(function(){var d=(document.body&&document.body.innerText)||"";return JSON.stringify({nodes:document.querySelectorAll("*").length,loading:d.indexOf("Loading")>=0,sw:window.innerWidth,ih:window.innerHeight,txt:d.slice(0,300)});})()'
    $msg=[Text.Encoding]::UTF8.GetBytes((@{id=1;method='Runtime.evaluate';params=@{expression=$expr;returnByValue=$true}}|ConvertTo-Json -Depth 6 -Compress))
    $ws.SendAsync([ArraySegment[byte]]::new($msg),'Text',$true,$cts.Token).GetAwaiter().GetResult()
    $buf=New-Object byte[] 262144; $sb=New-Object Text.StringBuilder
    for($i=0;$i -lt 6;$i++){
      $r=$ws.ReceiveAsync([ArraySegment[byte]]::new($buf),$cts.Token).GetAwaiter().GetResult()
      [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf,0,$r.Count))
      if($r.EndOfMessage){break}
    }
    "REPLY: $($sb.ToString())"
    $ws.Dispose()
  } catch { "CDP_FAIL: $($_.Exception.GetType().Name): $($_.Exception.Message)" }
}
