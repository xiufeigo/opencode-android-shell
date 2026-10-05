$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6210"; $PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v10"
$RAW=Join-Path $OUT "timing_raw.txt"; if(Test-Path $RAW){Remove-Item $RAW}
function L($m){ Add-Content -Path $RAW -Value $m -Encoding utf8 }
function A($argv){ $o=& $adb -s $S @argv 2>&1; return (($o|Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }

# child script: one CDP sample, fresh process => proven to work
$child = Join-Path $OUT "_cdp_sample.ps1"
@'
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
'@ | Set-Content $child -Encoding utf8

function Sample {
  $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $child 2>$null
  # the child leaks "System.Threading.Tasks.VoidTaskResult" lines => take the line that starts with '{'
  $line = ($o | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -like '{*' } | Select-Object -Last 1)
  if(-not $line){ return $null }
  try{ $j=$line|ConvertFrom-Json; return ($j.result.result.value|ConvertFrom-Json) }catch{ return $null }
}

L "# V10 TIMING 360dp vs 412dp  $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ss')"
L "# serial=$S  backend=127.0.0.1:4095 (Lead-owned)  pw=opencode  COLD=force-stop+3s+am start"
$cells=@(@{name="phone360dp";w=720;h=1280;d=320;dp=360}, @{name="phone412dp";w=824;h=1640;d=320;dp=412})
$rows=@()
foreach($c in $cells){
  L ""
  L "######## $($c.name) $($c.w)x$($c.h)@$($c.d) = $($c.dp)dp ########"
  A @('shell','wm','size',"$($c.w)x$($c.h)")|Out-Null
  A @('shell','wm','density',"$($c.d)")|Out-Null
  Start-Sleep -Seconds 4
  L "READBACK wmSize: $(A @('shell','wm','size'))"
  L "READBACK wmDensity: $(A @('shell','wm','density'))"
  for($r=1;$r -le 3;$r++){
    L ""
    L "---- $($c.name) RUN $r | COLD: force-stop + 3s + am start ----"
    A @('shell','am','force-stop',$PKG)|Out-Null
    Start-Sleep -Seconds 3
    A @('logcat','-c')|Out-Null
    $t0=Get-Date
    $am=A @('shell','am','start','-W','-n',$ACT)
    L "T+0 AM: $am"
    $shellMs=$null; $leftMs=$null
    for($k=1;$k -le 22;$k++){
      Start-Sleep -Milliseconds 700
      $el=[int]((Get-Date)-$t0).TotalMilliseconds
      $s = Sample
      if($s){
        L "T+${el}ms vp=$($s.sw)x$($s.ih) nodes=$($s.n) loading=$($s.ld) txt=$($s.t)"
        if($null -eq $shellMs -and [int]$s.n -gt 5){$shellMs=$el}
        if($null -eq $leftMs -and $null -ne $shellMs -and -not [bool]$s.ld){$leftMs=$el}
      } else { L "T+${el}ms CDP_NO_SAMPLE" }
      if($null -ne $leftMs -and $el -gt ($leftMs+900)){break}
    }
    L "RESULT cell=$($c.name) run=$r shellMs=$shellMs leftLoadingMs=$leftMs"
    A @('shell','screencap','-p',"/sdcard/t_$($c.name)_$r.png")|Out-Null
    A @('pull',"/sdcard/t_$($c.name)_$r.png",(Join-Path $OUT "t_$($c.name)_run$r.png"))|Out-Null
    $pl=A @('shell','logcat','-d','-s','OcWebHost/Proxy')
    L "PROXY_401=$(([regex]::Matches($pl,'401')).Count)  API_GETS=$((([regex]::Matches($pl,'GET http://127.0.0.1:4095/api/[^\s]*')|ForEach-Object{$_.Value}|Select-Object -Unique) -join ' ;; '))"
    L "PROBE=$(A @('shell','logcat','-d','-s','OcWebHost','|','grep','probe','|','tail','-2'))"
    $rows+=[pscustomobject]@{cell=$c.name;dp=$c.dp;run=$r;shellMs=$shellMs;leftLoadingMs=$leftMs}
  }
}
$rows|ConvertTo-Json -Depth 3|Set-Content (Join-Path $OUT "timing_results.json") -Encoding utf8
L "=== TIMING COMPLETE ==="
