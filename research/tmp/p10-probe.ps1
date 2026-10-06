$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$d = 'emulator-6790'
$pkg = 'com.xiufeigo.opencodeshell.web.debug'

function Invoke-CdpEval([string]$expr) {
  $pidStr = (& $adb -s $d shell pidof $pkg).Trim()
  if (-not $pidStr) { return "NOPID" }
  $ErrorActionPreference = 'Continue'
  & $adb -s $d forward tcp:9222 "localabstract:webview_devtools_remote_$pidStr" 2>&1 | Out-Null
  $ErrorActionPreference = 'Stop'
  $list = Invoke-RestMethod -Uri 'http://127.0.0.1:9222/json/list' -TimeoutSec 10
  $page = $list | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl } | Select-Object -First 1
  if (-not $page) { return "NOPAGE" }
  $ws = New-Object System.Net.WebSockets.ClientWebSocket
  $ct = New-Object System.Threading.CancellationTokenSource
  $ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, $ct.Token).Wait(15000) | Out-Null
  $msg = '{"id":1,"method":"Runtime.evaluate","params":{"expression":' + (ConvertTo-Json $expr -Compress) + ',"returnByValue":true,"awaitPromise":true}}'
  $bytes = [Text.Encoding]::UTF8.GetBytes($msg)
  $ws.SendAsync((New-Object System.ArraySegment[byte] (,$bytes)), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct.Token).Wait(15000) | Out-Null
  $buf = New-Object byte[] 65536
  $res = $ws.ReceiveAsync((New-Object System.ArraySegment[byte] (,$buf)), $ct.Token).Result
  $text = [Text.Encoding]::UTF8.GetString($buf, 0, $res.Count)
  $ws.Dispose()
  return $text
}

# 1) foreground
& $adb -s $d shell input keyevent 4 | Out-Null
Start-Sleep -Seconds 3
& $adb -s $d shell am start -n "$pkg/com.xiufeigo.opencodeshell.web.WebHostActivity" | Out-Null
Start-Sleep -Seconds 6

# 2) install probe + read pre-state
$probe = @'
(function(){
  var p = {ctor:0, req:0, read:0, titles:[]};
  var Orig = window.Notification;
  p.__origIsShim = (typeof Orig === "function") && !!Orig.requestPermission;
  function Wrapped(title, options){
    p.ctor++;
    try { p.titles.push(String(title)); } catch(e){}
    return new Orig(title, options);
  }
  Wrapped.requestPermission = function(){
    p.req++;
    try { return Orig.requestPermission.apply(Orig, arguments); } catch(e){ return Promise.resolve("default"); }
  };
  window.Notification = Wrapped;
  Object.defineProperty(Wrapped, "permission", {
    configurable: true,
    get: function(){ p.read++; return Orig.permission; }
  });
  window.__ocProbe = p;
  return JSON.stringify({installed:true, origIsShim:p.__origIsShim, vis:document.visibilityState, focus:document.hasFocus(), permNow:Orig.permission});
})()
'@
Write-Output ("INSTALL: " + (Invoke-CdpEval $probe))
