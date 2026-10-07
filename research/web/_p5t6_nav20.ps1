$ErrorActionPreference = 'Continue'
$S = 'emulator-5680'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
$CDP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\ui1\ui1_cdp.ps1'
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\nav.log" -Value $s -Encoding utf8; Write-Host $s }
function Ev([string]$js){
  $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $CDP -Js $js -Port 9222 -Serial $S 2>&1
  return ((($o | Out-String) -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'VoidTaskResult|socket=' }) -join '')
}

L "=== 4095 API: projects + sessions ==="
L ("projects: " + (Ev "(async()=>{try{const r=await fetch('http://127.0.0.1:4095/api/project',{headers:{authorization:'Basic '+btoa('opencode:opencode')}}); const j=await r.json(); return 'status='+r.status+' n='+(j.length||0)+' first='+JSON.stringify((j[0]||{}).id||j).slice(0,200);}catch(e){return 'ERR '+e}})()"))
L ("projects full: " + (Ev "(async()=>{try{const r=await fetch('http://127.0.0.1:4095/api/project',{headers:{authorization:'Basic '+btoa('opencode:opencode')}}); const j=await r.json(); return JSON.stringify((Array.isArray(j)?j:[j]).map(p=>({id:p.id,worktree:p.worktree,base:p.base||p.name})).slice(0,8));}catch(e){return 'ERR '+e}})()"))
