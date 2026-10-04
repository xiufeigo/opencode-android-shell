# coldstart_p3.ps1 — 冷启串行段对照：prewarm 前/后各 3 次
$ErrorActionPreference='Continue'
$Adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$P='com.xiufeigo.opencodeshell.web.debug'
$A='com.xiufeigo.opencodeshell.web.WebHostActivity'
$Dev='emulator-5636'
$Out="$PSScriptRoot\out\p3"
New-Item -ItemType Directory -Force -Path "$Out\coldstart" | Out-Null
function Adb2 { & $Adb -s $Dev @args }
foreach($run in 1..3){
  Adb2 shell am force-stop $P; Start-Sleep -Seconds 2
  Adb2 logcat -c | Out-Null
  $w = Adb2 shell am start -W -n "$P/$A" --es oc_perf_mode gzip
  $ls = ($w | Select-String 'LaunchState').ToString().Trim()
  $tt = ($w | Select-String 'TotalTime').ToString().Trim()
  Start-Sleep -Seconds 14
  $lc = Adb2 logcat -d -s OcPerf:V OcPerfJS:V
  $lc | Out-File -Encoding utf8 "$Out\coldstart\run$run.log"
  $marks=@{}
  foreach($k in 'A.onCreate.enter','B2.prewarmKicked','C.webviewCreated','I2.proxyPrewarmed','I.proxyListening','J.loadUrlIssued','Z.firstDraw'){
    $m=$lc | Select-String ([regex]::Escape($k)) | Select-Object -First 1
    if($m -and $m.Line -match "$([regex]::Escape($k))\s+t=(\d+)ms"){ $marks[$k]=[int]$Matches[1] } }
  $fcp=$lc | Select-String 'first-contentful-paint' | Select-Object -First 1
  $fcpm=$null; if($fcp -and $fcp.Line -match 'at=(\d+)'){ $fcpm=[int]$Matches[1] }
  $loadUrl = $marks['J.loadUrlIssued']
  "run${run}: $ls | $tt | loadUrl=$loadUrl ms | FCP=$fcpm ms | prewarm=$($marks['I2.proxyPrewarmed']) proxyListen=$($marks['I.proxyListening']) webview=$($marks['C.webviewCreated'])"
}
