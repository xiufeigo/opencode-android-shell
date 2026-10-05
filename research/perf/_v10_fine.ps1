$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6210"; $PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
$OUT="C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v10"
$RAW=Join-Path $OUT "fine_raw.txt"; if(Test-Path $RAW){Remove-Item $RAW}
function L($m){ Add-Content -Path $RAW -Value $m -Encoding utf8 }
function A($argv){ $o=& $adb -s $S @argv 2>&1; return (($o|Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }
$EXPR='(function(){try{var d=(document.body&&document.body.innerText)||"";return JSON.stringify({n:document.querySelectorAll("*").length,ld:d.indexOf("Loading")>=0,sw:window.innerWidth,ih:window.innerHeight,t:d.slice(0,60).replace(/[\r\n]+/g,"|")});}catch(e){return "E:"+e.message;}})()'

# ONE process per run => one persistent websocket => fast sampling
L "# V10 FINE-GRAINED 360 vs 412  $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ss')"
$cells=@(@{name="fine360dp";w=720;h=1280;d=320;dp=360}, @{name="fine412dp";w=824;h=1640;d=320;dp=412})
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
    A @('shell','am','force-stop',$PKG)|Out-Null
    Start-Sleep -Seconds 3
    A @('logcat','-c')|Out-Null
    $t0=Get-Date
    $am=A @('shell','am','start','-W','-n',$ACT)
    L ""
    L "---- $($c.name) RUN $r | COLD: force-stop+3s+am start ----"
    L "T+0 AM: $am"
    $shellMs=$null;$leftMs=$null;$ws=$null;$wsUrl=$null;$sockSeen=$null
    for($k=1;$k -le 60;$k++){
      $el=[int]((Get-Date)-$t0).TotalMilliseconds
      if($el -gt 20000){break}
      if($null -eq $wsUrl){
        $u=A @('shell','cat','/proc/net/unix')
        $m=[regex]::Matches($u,'webview_devtools_remote_\d+')
        if($m.Count -gt 0){
          $sockSeen=$m[0].Value
          A @('forward','--remove-all')|Out-Null
          A @('forward','tcp:9222',"localabstract:$sockSeen")|Out-Null
          try{ $j=Invoke-RestMethod -Uri 'http://127.0.0.1:9222/json' -TimeoutSec 4
               $pg=$j|Where-Object{$_.type -eq 'page'}|Select-Object -First 1
               if($pg){ $wsUrl=$pg.webSocketDebuggerUrl } }catch{}
        }
      }
      if($wsUrl -and ($null -eq $ws -or $ws.State -ne 'Open')){
        try{ $ws=[System.Net.WebSockets.ClientWebSocket]::new()
             $ws.ConnectAsync([Uri]$wsUrl,[Threading.CancellationToken]::None).GetAwaiter().GetResult()|Out-Null }catch{ $ws=$null }
      }
      if($ws){
        try{
          $msg=[Text.Encoding]::UTF8.GetBytes((@{id=$k;method='Runtime.evaluate';params=@{expression=$EXPR;returnByValue=$true}}|ConvertTo-Json -Depth 6 -Compress))
          $cts=[Threading.CancellationTokenSource]::new(6000)
          $ws.SendAsync([ArraySegment[byte]]::new($msg),'Text',$true,$cts.Token).GetAwaiter().GetResult()|Out-Null
          $buf=New-Object byte[] 262144;$sb=New-Object Text.StringBuilder
          for($i=0;$i -lt 6;$i++){
            $rr=$ws.ReceiveAsync([ArraySegment[byte]]::new($buf),$cts.Token).GetAwaiter().GetResult()
            [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf,0,$rr.Count))
            if($rr.EndOfMessage){break}
          }
          $line=($sb.ToString() -split "`n"|Where-Object{$_.Trim() -like '{*'}|Select-Object -Last 1)
          if($line){
            $val=($line|ConvertFrom-Json).result.result.value|ConvertFrom-Json
            $el2=[int]((Get-Date)-$t0).TotalMilliseconds
            $hasText = ($val.t -and $val.t.Trim().Length -gt 0)
            L "T+${el2}ms vp=$($val.sw)x$($val.ih) nodes=$($val.n) loading=$($val.ld) txt=$($val.t)"
            if($null -eq $shellMs -and [int]$val.n -gt 5){$shellMs=$el2}
            # "left Loading" = skeleton gone AND real text present (skeleton itself renders the word Loading)
            if($null -eq $leftMs -and $null -ne $shellMs -and -not [bool]$val.ld -and $hasText){$leftMs=$el2}
          }
        }catch{ try{$ws.Dispose()}catch{}; $ws=$null; $wsUrl=$null }
      } else { L "T+${el}ms no_ws sock=$sockSeen" }
      if($null -ne $leftMs){break}
      Start-Sleep -Milliseconds 150
    }
    L "RESULT cell=$($c.name) run=$r shellMs=$shellMs leftLoadingMs=$leftMs"
    A @('shell','screencap','-p',"/sdcard/f_$($c.name)_$r.png")|Out-Null
    A @('pull',"/sdcard/f_$($c.name)_$r.png",(Join-Path $OUT "f_$($c.name)_run$r.png"))|Out-Null
    L "PROBE=$(A @('shell','logcat','-d','-s','OcWebHost','|','grep','-E','probe|home=','|','tail','-3'))"
    $pl=A @('shell','logcat','-d','-s','OcWebHost/Proxy')
    L "PROXY_401=$(([regex]::Matches($pl,'401')).Count) API_GETS=$((([regex]::Matches($pl,'GET http://127.0.0.1:4095/api/[^\s]*')|ForEach-Object{$_.Value}|Select-Object -Unique) -join ' ;; '))"
    $rows+=[pscustomobject]@{cell=$c.name;dp=$c.dp;run=$r;shellMs=$shellMs;leftLoadingMs=$leftMs}
    if($ws){try{$ws.Dispose()}catch{}}
  }
}
$rows|ConvertTo-Json -Depth 3|Set-Content (Join-Path $OUT "fine_results.json") -Encoding utf8
L "=== FINE COMPLETE ==="
