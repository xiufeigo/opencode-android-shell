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
function Tap($x,$y){ Sh "input tap $x $y" | Out-Null; Start-Sleep -Milliseconds 1100 }

L "=== REAL TAP: Home -> projects dropdown ==="
# go home via real nav
L ("nav home: " + (Ev "(()=>{location.href='/'; return 'OK';})()"))
Start-Sleep -Seconds 4
L ("url: " + (Ev "location.href"))
L ("body: " + (Ev "document.body.innerText.slice(0,200)"))
# projects dropdown button center
$rect = Ev "(()=>{const b=document.querySelector('button[data-component=home-projects-dropdown]'); if(!b)return 'NODD'; const r=b.getBoundingClientRect(); return Math.round(r.x+r.width/2)+' '+Math.round(r.y+r.height/2);})()"
L ("dd rect: $rect")
if ($rect -ne 'NODD') {
  $p = $rect -split ' '
  Tap $p[0] $p[1]
  Shot '23-dd-real'
  L ("expanded: " + (Ev "(()=>{const b=document.querySelector('button[data-component=home-projects-dropdown]'); return b?b.getAttribute('aria-expanded'):'gone';})()"))
  L ("MENU: " + (Ev "(()=>{const m=[...document.querySelectorAll('[role=menu],[role=menuitem],[role=dialog]')].map(e=>e.innerText.trim().replace(/\n/g,'|')); return JSON.stringify(m.slice(0,10));})()"))
  L ("4095 nodes: " + (Ev "(()=>{const o=[];document.querySelectorAll('*').forEach(e=>{if(e.children.length===0&&/4095/.test(e.textContent||'')){const r=e.getBoundingClientRect();o.push(e.tagName+'@'+Math.round(r.x)+','+Math.round(r.y)+' w='+Math.round(r.width));}});return JSON.stringify(o.slice(0,8));})()"))
}
