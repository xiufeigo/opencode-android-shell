$ErrorActionPreference = 'Continue'
$S = 'emulator-5680'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
$CDP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\ui1\ui1_cdp.ps1'
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\nav.log" -Value $s -Encoding utf8; Write-Host $s }
function Ev([string]$js){
  $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $CDP -Js $js -Port 9222 -Serial $S 2>&1
  return ((($o | Out-String) -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'VoidTaskResult|socket=' }) -join '')
}
$B64 = 'aHR0cDovLzEyNy4wLjAuMTo0MDk1'

L "=== SCAN ALL 4095 PROJECTS FOR SESSIONS ==="
$js = @"
(async()=>{
 try{
  const h={authorization:'Basic '+btoa('opencode:opencode')};
  const base='http://127.0.0.1:4095';
  const pj=await (await fetch(base+'/api/project',{headers:h})).json();
  const projs=Array.isArray(pj)?pj:[pj];
  const out=[];
  for(const p of projs){
    try{
      const r=await fetch(base+'/api/session?project='+p.id,{headers:h});
      const j=await r.json(); const arr=Array.isArray(j)?j:[];
      if(arr.length) out.push({proj:p.id,n:arr.length,sid:arr[0].id,title:arr[0].title,updated:arr[0].time&&arr[0].time.updated});
    }catch(e){}
  }
  return JSON.stringify(out);
 }catch(e){return 'ERR '+e}
})()
"@
$res = Ev $js
L ("scanned: $res")
$o = $res | ConvertFrom-Json
if ($o -and $o.Count -gt 0) {
  $pick = $o[0]
  $sid = $pick.sid
  L "PICK proj=$($pick.proj) sid=$sid n=$($pick.n) title=$($pick.title)"
  $pick | ConvertTo-Json -Compress | Out-File "$OUT\picked_session.json" -Encoding utf8
}
