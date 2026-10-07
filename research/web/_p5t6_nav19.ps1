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

L "=== VIEWPORT / SCALE ==="
L ("vp: " + (Ev "JSON.stringify({iw:innerWidth,ih:innerHeight,dpr:devicePixelRatio})"))
$sw = (Sh "wm size")
L ("wm size: $sw")

# device scale = screen width / innerWidth
$vp = Ev "JSON.stringify({iw:innerWidth,dpr:devicePixelRatio})" | ConvertFrom-Json
$scale = [double]$vp.dpr
L ("scale (dpr) = $scale")

L "=== SCALED TAP on projects dropdown ==="
L ("nav home: " + (Ev "(()=>{location.href='/'; return 'OK';})()"))
Start-Sleep -Seconds 4
$rect = Ev "(()=>{const b=document.querySelector('button[data-component=home-projects-dropdown]'); if(!b)return 'NODD'; const r=b.getBoundingClientRect(); return Math.round(r.x+r.width/2)+' '+Math.round(r.y+r.height/2);})()"
L ("dd css rect: $rect")
$p = $rect -split ' '
$dx = [int]([double]$p[0] * $scale); $dy = [int]([double]$p[1] * $scale)
L ("tapping device $dx,$dy")
Sh "input tap $dx $dy" | Out-Null
Start-Sleep -Seconds 2
Shot '24-dd-scaled'
L ("expanded: " + (Ev "(()=>{const b=document.querySelector('button[data-component=home-projects-dropdown]'); return b?b.getAttribute('aria-expanded'):'gone';})()"))
L ("menu texts: " + (Ev "JSON.stringify([...document.querySelectorAll('[role=menu],[role=menuitem],[role=dialog],[role=listbox]')].map(e=>e.innerText.trim().replace(/\n/g,'|')).slice(0,10))"))
L ("4095 css pos: " + (Ev "(()=>{const o=[];document.querySelectorAll('*').forEach(e=>{if(e.children.length===0&&/4095/.test(e.textContent||'')){const r=e.getBoundingClientRect();o.push(e.tagName+'@'+Math.round(r.x+r.width/2)+','+Math.round(r.y+r.height/2)+' w='+Math.round(r.width));}});return JSON.stringify(o.slice(0,8));})()"))
