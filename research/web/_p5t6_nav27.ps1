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

L "=== CREATE SESSION ON 4095 ==="
$js = @"
(async()=>{
 try{
  const h={authorization:'Basic '+btoa('opencode:opencode'),'content-type':'application/json'};
  const base='http://127.0.0.1:4095';
  const pj=await (await fetch(base+'/api/project',{headers:h})).json();
  const pid=(Array.isArray(pj)?pj:[pj])[0].id;
  const out={pid};
  let r=await fetch(base+'/api/session?project='+pid,{method:'POST',headers:h,body:JSON.stringify({projectID:pid})});
  out.s1=r.status; try{out.body1=(await r.text()).slice(0,200);}catch(e){}
  if(!r.ok){
    r=await fetch(base+'/api/session',{method:'POST',headers:h,body:JSON.stringify({projectID:pid,directory:'D:/XiaoZhuTouTou/OpenCodeUI'})});
    out.s2=r.status; try{out.body2=(await r.text()).slice(0,200);}catch(e){}
  }
  const chk=await (await fetch(base+'/api/session',{headers:h})).json();
  out.after_n=(Array.isArray(chk)?chk:[]).length;
  out.sid=(Array.isArray(chk)?chk:[])[0]&&chk[0].id;
  return JSON.stringify(out);
 }catch(e){return 'ERR '+e}
})()
"@
$res = Ev $js
L ("create: $res")
$o = $res | ConvertFrom-Json
if ($o.sid) {
  L ("=== NAV to /server/B64/session/SID ===")
  L ("nav -> " + (Ev "(()=>{location.href='/server/$B64/session/$($o.sid)'; return 'OK';})()"))
  Start-Sleep -Seconds 8
  L ("url: " + (Ev "location.href"))
  L ("body: " + (Ev "document.body.innerText.slice(0,400)"))
  Shot '30-sess'
  L ("composer: " + (Ev "JSON.stringify([...document.querySelectorAll('textarea,[contenteditable=true]')].map(e=>e.tagName+'|'+(e.placeholder||e.getAttribute('data-placeholder')||e.innerText||'').slice(0,30)))"))
}
