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

L ("logcat alive: " + (Get-Process -Id (Get-Content "$OUT\logcat.pid") -ErrorAction SilentlyContinue).Id)
L "=== SEARCH ANY 4095 ELEMENT ==="
L ("any4095: " + (Ev "(()=>{const out=[];document.querySelectorAll('*').forEach(e=>{if(e.children.length===0&&/4095/.test(e.textContent||'')){const r=e.getBoundingClientRect();out.push(e.tagName+'|'+e.textContent.trim().slice(0,40)+'|'+Math.round(r.x)+','+Math.round(r.y)+'|'+e.className.slice(0,30));}});return JSON.stringify(out.slice(0,12));})()"))
L ("allLinks: " + (Ev "JSON.stringify([...document.querySelectorAll('a')].map(a=>(a.getAttribute('href')||'')))"))

# open drawer (real full-width tap per P4)
Sh "input keyevent 4" | Out-Null; Start-Sleep -Milliseconds 800
Sh "input tap 532 121" | Out-Null; Start-Sleep -Seconds 2
Shot '17-drawer'
L ("drawer body: " + (Ev "document.body.innerText.slice(0,400)"))
L ("drawer 4095: " + (Ev "(()=>{const out=[];document.querySelectorAll('*').forEach(e=>{if(e.children.length===0&&/4095/.test(e.textContent||'')){const r=e.getBoundingClientRect();out.push(e.tagName+'|'+e.textContent.trim().slice(0,40)+'|'+Math.round(r.x)+','+Math.round(r.y));}});return JSON.stringify(out.slice(0,10));})()"))
