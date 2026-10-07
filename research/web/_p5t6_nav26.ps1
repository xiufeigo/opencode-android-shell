$ErrorActionPreference = 'Continue'
$S = 'emulator-5680'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
$CDP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\ui1\ui1_cdp.ps1'
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\nav.log" -Value $s -Encoding utf8; Write-Host $s }
function Ev([string]$js){
  $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $CDP -Js $js -Port 9222 -Serial $S 2>&1
  return ((($o | Out-String) -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'VoidTaskResult|socket=' }) -join '')
}

L "=== SESSIONS: global + shapes ==="
$js = @"
(async()=>{
 try{
  const h={authorization:'Basic '+btoa('opencode:opencode')};
  const base='http://127.0.0.1:4095';
  const out={};
  try{const r=await fetch(base+'/api/session',{headers:h});const j=await r.json();out.global_status=r.status;out.global_n=(Array.isArray(j)?j:[]).length;out.global_sample=(Array.isArray(j)?j:[j]).slice(0,4).map(s=>({id:s.id,title:s.title,dir:s.directory,pj:s.projectID}));}catch(e){out.global='ERR '+e}
  try{const r=await fetch(base+'/api/session/status',{headers:h});const j=await r.json();out.status_n=(Array.isArray(j)?j:Object.keys(j||{})).length;}catch(e){out.status='ERR '+e}
  return JSON.stringify(out);
 }catch(e){return 'ERR '+e}
})()
"@
L (Ev $js)
