$ErrorActionPreference='Continue'
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$S="emulator-6210"; $PKG="com.xiufeigo.opencodeshell.web.debug"
$ACT="$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
function A($argv){ $o = & $adb -s $S @argv 2>&1; return (($o | Out-String) -replace "`r`n"," | ") -replace '\s{2,}',' ' }

"=== clear app data (drops stale SecretStore password) ==="
A @('shell','pm','clear',$PKG)
"=== relaunch ==="
A @('shell','am','start','-n',$ACT)
Start-Sleep -Seconds 6
A @('shell','uiautomator','dump','/sdcard/d1.xml') | Out-Null
$d = A @('shell','cat','/sdcard/d1.xml')
"HAS_SETUP_DIALOG: $($d -match '配置 opencode 后端')"
"BTN_TEXTS: $((([regex]::Matches($d,'text="([^"]*)"[^>]*resource-id="android:id/button\d"') | ForEach-Object { $_.Groups[1].Value }) -join '/'))"
$et = [regex]::Matches($d,'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
"EDITTEXT_COUNT=$($et.Count)"
if ($et.Count -ge 2) {
  $c1x=[int](($et[0].Groups[1].Value -as [int])+($et[0].Groups[3].Value -as [int]))/2
  $c1y=[int](($et[0].Groups[2].Value -as [int])+($et[0].Groups[4].Value -as [int]))/2
  "PASS_FIELD tap=($c1x,$c1y)"
  A @('shell','input','tap',"$c1x","$c1y")
  Start-Sleep -Milliseconds 600
  A @('shell','input','text','opencode')
  Start-Sleep -Milliseconds 600
}
$b = [regex]::Match($d,'text="保存并连接"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if ($b.Success) {
  $bx=[int](($b.Groups[1].Value -as [int])+($b.Groups[3].Value -as [int]))/2
  $by=[int](($b.Groups[2].Value -as [int])+($b.Groups[4].Value -as [int]))/2
  "SAVE tap=($bx,$by)"
  A @('shell','input','tap',"$bx","$by")
} else { "SAVE_BTN_NOT_FOUND" }
Start-Sleep -Seconds 8
"LOG: $(A @('shell','logcat','-d','-s','OcWebHost','OcWebHost/Proxy'))"
A @('shell','screencap','-p','/sdcard/seed2.png') | Out-Null
A @('pull','/sdcard/seed2.png','C:\sensorsdata\main\program\opencode-android-shell\research\perf\out_v10\seed2.png') | Out-Null

"=== CDP DEBUG ==="
$u = A @('shell','cat','/proc/net/unix')
"socket: $((([regex]::Matches($u,'webview_devtools_remote_\d+') | ForEach-Object { $_.Value }) -join ','))"
A @('forward','--remove-all') | Out-Null
"fwd: $(A @('forward','tcp:9222','localabstract:webview_devtools_remote_3359'))"
try { $j = Invoke-RestMethod -Uri 'http://127.0.0.1:9222/json' -TimeoutSec 6; "json: $($j | ConvertTo-Json -Depth 3 -Compress)" } catch { "JSON_FAIL: $($_.Exception.Message)" }
$pg = $j | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
"WSURL=$($pg.webSocketDebuggerUrl)"
try {
  $ws=[System.Net.WebSockets.ClientWebSocket]::new()
  $cts=[Threading.CancellationTokenSource]::new(8000)
  $ws.ConnectAsync([Uri]$pg.webSocketDebuggerUrl,$cts.Token).GetAwaiter().GetResult()
  "WS_CONNECTED state=$($ws.State)"
  $expr='(function(){var d=(document.body&&document.body.innerText)||"";return JSON.stringify({nodes:document.querySelectorAll("*").length,loading:d.indexOf("Loading")>=0,sw:window.innerWidth,txt:d.slice(0,200)});})()'
  $msg=[Text.Encoding]::UTF8.GetBytes((@{id=1;method='Runtime.evaluate';params=@{expression=$expr;returnByValue=$true}}|ConvertTo-Json -Depth 6 -Compress))
  $ws.SendAsync([ArraySegment[byte]]::new($msg),'Text',$true,$cts.Token).GetAwaiter().GetResult()
  "SENT"
  $buf=New-Object byte[] 131072; $sb=New-Object Text.StringBuilder
  for($i=0;$i -lt 5;$i++){
    $r=$ws.ReceiveAsync([ArraySegment[byte]]::new($buf),$cts.Token).GetAwaiter().GetResult()
    [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf,0,$r.Count))
    "recv count=$($r.Count) end=$($r.EndOfMessage) type=$($r.MessageType)"
    if($r.EndOfMessage){break}
  }
  "REPLY: $($sb.ToString())"
  $ws.Dispose()
} catch { "CDP_FAIL: $($_.Exception.GetType().Name): $($_.Exception.Message)" }
