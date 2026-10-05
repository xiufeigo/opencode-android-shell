# UI1 浏览器侧取证助手（只读轮）
# 用途: 用本机 Chrome + CDP 精确设定视口并把官方 web UI 截图落盘。
# 不用 --screenshot 的原因: 官方 UI 持有常连 SSE(/api/event)，页面永不 idle，
# headless 的 --screenshot / --virtual-time-budget 一直不返回。
param(
  [Parameter(Mandatory=$true)][string]$Out,
  [Parameter(Mandatory=$true)][int]$Width,
  [Parameter(Mandatory=$true)][int]$Height,
  [string]$Url = 'http://127.0.0.1:4095/',
  [int]$CdpPort = 9333,
  [int]$Dsf = 1,
  [bool]$Mobile = $false,
  [string]$Js = '',
  [int]$WaitSec = 9
)
$ErrorActionPreference = 'Stop'
$ch = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$udd = "$env:TEMP\oc_ui1_chrome_$CdpPort"
$proc = Start-Process -FilePath $ch -ArgumentList @(
  '--headless=new','--disable-gpu','--no-sandbox','--no-first-run','--no-default-browser-check',
  '--allow-insecure-localhost','--allow-running-insecure-content',
  '--unsafely-treat-insecure-origin-as-secure=http://127.0.0.1:4095',
  "--user-data-dir=$udd","--remote-debugging-port=$CdpPort",'about:blank'
) -PassThru -WindowStyle Hidden

# 等 devtools 端点就绪
$ver = $null
for ($i=0; $i -lt 40; $i++) {
  try { $ver = Invoke-RestMethod "http://127.0.0.1:$CdpPort/json/version" -TimeoutSec 2; break } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $ver) { Write-Output "ERR_NO_CDP"; try{$proc.Kill()}catch{}; exit 1 }

# 复用已存在的 page target（/json/new 在本机被拒）
$target = $null
for ($i=0; $i -lt 20; $i++) {
  try {
    $lst = Invoke-RestMethod "http://127.0.0.1:$CdpPort/json/list" -TimeoutSec 5
    $target = $lst | Where-Object { $_.type -eq 'page' -and $_.webSocketDebuggerUrl } | Select-Object -First 1
    if ($target) { break }
  } catch {}
  Start-Sleep -Milliseconds 400
}
if (-not $target) { Write-Output "ERR_NO_TARGET"; try{$proc.Kill()}catch{}; exit 1 }
$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(60))
$ws.ConnectAsync([Uri]$target.webSocketDebuggerUrl, $cts.Token).GetAwaiter().GetResult()

$script:nextId = 0
function Send-Cdp([string]$method, $params) {
  $script:nextId++
  $id = $script:nextId
  $payload = @{ id=$id; method=$method; params=$params } | ConvertTo-Json -Depth 10 -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
  $ws.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult()
  $buf = New-Object byte[] 4194304
  while ($true) {
    $res = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $cts.Token).GetAwaiter().GetResult()
    if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { throw "closed" }
    $txt = [System.Text.Encoding]::UTF8.GetString($buf, 0, $res.Count)
    try { $j = $txt | ConvertFrom-Json } catch { continue }
    if ($j.id -eq $id) { return $j }
  }
}

Send-Cdp 'Page.enable' @{} | Out-Null
# 认证方式: 用 CDP 给所有请求(含 XHR/SSE)加 Basic 头。
# 不能用 URL 内嵌 userinfo —— Chrome 会判为非本地明文并报 ERR_CLEARTEXT_NOT_PERMITTED。
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('opencode:opencode'))
Send-Cdp 'Network.enable' @{} | Out-Null
Send-Cdp 'Network.setExtraHTTPHeaders' @{ headers = @{ Authorization = "Basic $basic" } } | Out-Null
Send-Cdp 'Emulation.setDeviceMetricsOverride' @{
  width=$Width; height=$Height; deviceScaleFactor=$Dsf; mobile=$Mobile } | Out-Null
Send-Cdp 'Page.navigate' @{ url = $Url } | Out-Null
Start-Sleep -Seconds $WaitSec
if ($Js) {
  $r = Send-Cdp 'Runtime.evaluate' @{ expression=$Js; returnByValue=$true; awaitPromise=$true }
  Write-Output ("# js: " + ($r.result.result.value | ConvertTo-Json -Depth 8 -Compress))
  Start-Sleep -Seconds 2
}
$shot = Send-Cdp 'Page.captureScreenshot' @{ format='png' }
$b64 = $shot.result.data
[IO.File]::WriteAllBytes($Out, [Convert]::FromBase64String($b64))
Write-Output ("SAVED $Out bytes=" + (Get-Item $Out).Length)

$ws.Dispose()
try { $proc.Kill() } catch {}
