$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S = 'emulator-5680'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
$SHOTS = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\p5t6_shots'
$CDP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\ui1\ui1_cdp.ps1'
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\nav.log" -Value $s -Encoding utf8; Write-Host $s }
function Sh($c){ $o = & $adb -s $S shell $c 2>&1; return (($o | Out-String) -replace "`r`n",' | ') -replace '\s{2,}',' ' }
function Ev([string]$js){
  $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $CDP -Js $js -Port 9222 -Serial $S 2>&1
  return ((($o | Out-String) -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'VoidTaskResult|socket=' }) -join '')
}
function Shot($n){ & $adb -s $S shell "screencap -p /sdcard/$n.png" 2>&1|Out-Null; & $adb -s $S pull "/sdcard/$n.png" "$SHOTS\$n.png" 2>&1|Out-Null; L "shot $n" }
$B64 = 'aHR0cDovLzEyNy4wLjAuMTo0MDk1'

L "=== GET SESSION ID from 4095 API ==="
$sess = Ev "(async()=>{try{const h={authorization:'Basic '+btoa('opencode:opencode')}; const p=await (await fetch('http://127.0.0.1:4095/api/project',{headers:h})).json(); const pid=(Array.isArray(p)?p:[p])[0].id; const r=await fetch('http://127.0.0.1:4095/api/session?project='+pid,{headers:h}); const j=await r.json(); const arr=Array.isArray(j)?j:[]; return JSON.stringify({proj:pid,n:arr.length,first:arr[0]?arr[0].id:null,title:arr[0]&&arr[0].title});}catch(e){return 'ERR '+e}})()"
L ("sessions: $sess")
$o = $sess | ConvertFrom-Json
if ($o.first) {
  $sid = $o.first
  L "=== ROUTE /server/B64/session/SID ==="
  L ("nav -> " + (Ev "(()=>{location.href='/server/$B64/session/$sid'; return 'OK';})()"))
  Start-Sleep -Seconds 8
  L ("url: " + (Ev "location.href"))
  L ("body: " + (Ev "document.body.innerText.slice(0,400)"))
  Shot '27-session4095'
  L ("composer: " + (Ev "JSON.stringify([...document.querySelectorAll('textarea,input[data-placeholder],[contenteditable=true]')].map(e=>e.tagName+'|'+(e.placeholder||e.getAttribute('data-placeholder')||'')))"))
}
