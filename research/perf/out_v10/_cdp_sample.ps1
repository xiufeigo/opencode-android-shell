$ErrorActionPreference='Stop'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6210"
function A($argv){ $o=& $adb -s $S @argv 2>&1; return (($o|Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
$u = A @('shell','cat','/proc/net/unix')
$m = [regex]::Matches($u,'webview_devtools_remote_\d+')
if($m.Count -eq 0){ Write-Output "NOSOCK"; exit 0 }
$sock = $m[0].Value
A @('forward','--remove-all') | Out-Null
A @('forward','tcp:9222',"localabstract:$sock") | Out-Null
try{ $j = Invoke-RestMethod -Uri 'http://127.0.0.1:9222/json' -TimeoutSec 5 }catch{ Write-Output "NOJSON"; exit 0 }
$pg = $j | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
if(-not $pg){ Write-Output "NOPAGE"; exit 0 }
try{
  $ws=[System.Net.WebSockets.ClientWebSocket]::new()
  $ws.ConnectAsync([Uri]$pg.webSocketDebuggerUrl,[Threading.CancellationToken]::None).GetAwaiter().GetResult()
  $expr='(function(){try{var d=(document.body&&document.body.innerText)||"";return JSON.stringify({n:document.querySelectorAll("*").length,ld:d.indexOf("Loading")>=0,sw:window.innerWidth,ih:window.innerHeight,t:d.slice(0,90).replace(/[\r\n]+/g,"|")});}catch(e){return "E:"+e.message;}})()'
  $msg=[Text.Encoding]::UTF8.GetBytes((@{id=1;method='Runtime.evaluate';params=@{expression=$expr;returnByValue=$true}}|ConvertTo-Json -Depth 6 -Compress))
  $cts=[Threading.CancellationTokenSource]::new(8000)
  $ws.SendAsync([ArraySegment[byte]]::new($msg),'Text',$true,$cts.Token).GetAwaiter().GetResult()
  $buf=New-Object byte[] 262144; $sb=New-Object Text.StringBuilder
  for($i=0;$i -lt 6;$i++){
    $r=$ws.ReceiveAsync([ArraySegment[byte]]::new($buf),$cts.Token).GetAwaiter().GetResult()
    [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf,0,$r.Count))
    if($r.EndOfMessage){break}
  }
  Write-Output $sb.ToString(); $ws.Dispose()
}catch{ Write-Output "CDPFAIL" }
