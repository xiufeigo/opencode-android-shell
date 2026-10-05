$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6210"; $PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v10"
$RAW=Join-Path $OUT "sweep_raw.txt"; if(Test-Path $RAW){Remove-Item $RAW}
function L($m){ Add-Content -Path $RAW -Value $m -Encoding utf8 }
function A($argv){ $o=& $adb -s $S @argv 2>&1; return (($o|Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
function LiveSock { $u=A @('shell','cat','/proc/net/unix'); $m=[regex]::Matches($u,'webview_devtools_remote_\d+'); if($m.Count -gt 0){return $m[0].Value}; return $null }
$script:wsUrl=$null
function InitCdp {
  $sock=LiveSock; if(-not $sock){return $false}
  A @('forward','--remove-all')|Out-Null
  A @('forward','tcp:9222',"localabstract:$sock")|Out-Null
  try{
    $j=Invoke-RestMethod -Uri 'http://127.0.0.1:9222/json' -TimeoutSec 6
    $pg=$j|Where-Object{$_.type -eq 'page'}|Select-Object -First 1
    if($pg){$script:wsUrl=$pg.webSocketDebuggerUrl; return $true}
  }catch{}
  return $false
}
$script:ws=$null
function CdpEval([string]$expr){
  if(-not $script:wsUrl){return $null}
  try{
    if($null -eq $script:ws -or $script:ws.State -ne 'Open'){
      $script:ws=[System.Net.WebSockets.ClientWebSocket]::new()
      $script:ws.ConnectAsync([Uri]$script:wsUrl,[Threading.CancellationToken]::None).GetAwaiter().GetResult()
    }
    $cts=[Threading.CancellationTokenSource]::new(8000)
    $msg=[Text.Encoding]::UTF8.GetBytes((@{id=1;method='Runtime.evaluate';params=@{expression=$expr;returnByValue=$true}}|ConvertTo-Json -Depth 6 -Compress))
    $script:ws.SendAsync([ArraySegment[byte]]::new($msg),'Text',$true,$cts.Token).GetAwaiter().GetResult()
    $buf=New-Object byte[] 262144; $sb=New-Object Text.StringBuilder
    for($i=0;$i -lt 6;$i++){
      $r=$script:ws.ReceiveAsync([ArraySegment[byte]]::new($buf),$cts.Token).GetAwaiter().GetResult()
      [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf,0,$r.Count))
      if($r.EndOfMessage){break}
    }
    return $sb.ToString()
  }catch{ $script:ws=$null; return "CDPERR:$($_.Exception.Message)" }
}
$EXPR='(function(){try{var d=(document.body&&document.body.innerText)||"";var n=document.querySelectorAll("*").length;var ld=d.indexOf("Loading")>=0;return JSON.stringify({nodes:n,loading:ld,sw:window.innerWidth,ih:window.innerHeight,txt:d.slice(0,120).replace(/[\r\n]+/g,"|")});}catch(e){return "EVALERR:"+e.message;}})()'

L "# V10 controlled 360dp vs 412dp $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ss')"
L "# serial=$S  backend=127.0.0.1:4095 (Lead-owned, never started/stopped by me)  pw=opencode"
L "REVERSE: $(A @('reverse','tcp:4095','tcp:4095'))"

$cells=@(@{name="phone360dp";w=720;h=1280;d=320;dp=360}, @{name="phone412dp";w=824;h=1640;d=320;dp=412})
$rows=@()
foreach($c in $cells){
  L ""
  L "######## $($c.name) target=$($c.dp)dp wm=$($c.w)x$($c.h)@$($c.d) ########"
  A @('shell','wm','size',"$($c.w)x$($c.h)")|Out-Null
  A @('shell','wm','density',"$($c.d)")|Out-Null
  Start-Sleep -Seconds 4
  L "READBACK wmSize: $(A @('shell','wm','size'))"
  L "READBACK wmDensity: $(A @('shell','wm','density'))"
  L "READBACK smallestDp: $(A @('shell','dumpsys','window','displays','|','grep','-m','1','app'))"

  for($r=1;$r -le 3;$r++){
    L ""
    L "---- $($c.name) RUN $r | COLD = force-stop + 3s + am start ----"
    A @('shell','am','force-stop',$PKG)|Out-Null
    Start-Sleep -Seconds 3
    A @('logcat','-c')|Out-Null
    $script:wsUrl=$null; $script:ws=$null
    $t0=Get-Date
    $am=A @('shell','am','start','-W','-n',$ACT)
    L "T+0 AM_RAW: $am"
    $shellMs=$null; $leftMs=$null
    for($k=1;$k -le 20;$k++){
      Start-Sleep -Milliseconds 1200
      $el=[int]((Get-Date)-$t0).TotalMilliseconds
      if(-not $script:wsUrl){[void](InitCdp)}
      $cdp=CdpEval $EXPR
      $val=$null
      if($cdp -and $cdp -notmatch '^CDPERR'){ try{ $j=$cdp|ConvertFrom-Json; $val=$j.result.result.value|ConvertFrom-Json }catch{} }
      if($val){
        L "T+${el}ms vp=$($val.sw)x$($val.ih) nodes=$($val.nodes) loading=$($val.loading) txt=$($val.txt)"
        if($null -eq $shellMs -and [int]$val.nodes -gt 5){$shellMs=$el}
        if($null -eq $leftMs -and $null -ne $shellMs -and -not [bool]$val.loading){$leftMs=$el}
      } else {
        L "T+${el}ms CDP_UNAVAILABLE $(if($cdp){$cdp.Substring(0,[Math]::Min(120,$cdp.Length))}else{'no-socket'})"
      }
      if($null -ne $leftMs -and $el -gt ($leftMs+1200)){break}
    }
    L "RESULT cell=$($c.name) run=$r shellMs=$shellMs leftLoadingMs=$leftMs"
    A @('shell','screencap','-p',"/sdcard/v10_$($c.name)_$r.png")|Out-Null
    $png=Join-Path $OUT "$($c.name)_run$r.png"
    A @('pull',"/sdcard/v10_$($c.name)_$r.png",$png)|Out-Null
    L "SCREENSHOT $png bytes=$((Get-Item $png -ErrorAction SilentlyContinue).Length)"
    $pl=A @('shell','logcat','-d','-s','OcWebHost/Proxy')
    L "PROXY_401_COUNT=$(([regex]::Matches($pl,'401')).Count) PROXY_5XX=$(([regex]::Matches($pl,'5\d\d')).Count)"
    L "PROXY_API_GETS=$((([regex]::Matches($pl,'GET http://127.0.0.1:4095/api/[^\s]*')|ForEach-Object{$_.Value}|Select-Object -Unique) -join ' ;; '))"
    L "PROBE_LINE=$(A @('shell','logcat','-d','-s','OcWebHost','|','grep','probe'))"
    $rows+=[pscustomobject]@{cell=$c.name;dp=$c.dp;run=$r;shellMs=$shellMs;leftLoadingMs=$leftMs}
    if($script:ws){try{$script:ws.Dispose()}catch{};$script:ws=$null}
  }
}
$rows|ConvertTo-Json -Depth 3|Set-Content (Join-Path $OUT "results.json") -Encoding utf8
L ""
L "=== V10 sweep complete ==="
