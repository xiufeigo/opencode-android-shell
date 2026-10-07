$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S = 'emulator-5680'
$PKG = 'com.xiufeigo.opencodeshell.web.debug'
$ACT = "$PKG/com.xiufeigo.opencodeshell.web.WebHostActivity"
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
$DPR = 2.625
function TapCss($x,$y){ $dx=[int]($x*$DPR); $dy=[int]($y*$DPR); L "  tap dev($dx,$dy)"; Sh "input tap $dx $dy" | Out-Null; Start-Sleep -Milliseconds 1100 }

L "=== CLEAN RESTART ==="
L ("stop: " + (Sh "am force-stop $PKG")); Start-Sleep -Seconds 2
L ("start: " + (Sh "am start -n $ACT")); Start-Sleep -Seconds 12
$p = (Sh "pidof $PKG"); L ("pid: $p")
& $adb -s $S forward --remove-all 2>&1|Out-Null
$unix = (Sh "cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1")
& $adb -s $S forward tcp:9222 "localabstract:$unix" 2>&1|Out-Null
Start-Sleep -Seconds 5
L ("url: " + (Ev "location.href"))
L ("body: " + (Ev "document.body.innerText.slice(0,300)"))
Shot '28-clean-home'

L "=== OPEN PROJECTS DROPDOWN (scaled) ==="
$rect = Ev "(()=>{const b=document.querySelector('button[data-component=home-projects-dropdown]'); if(!b)return 'NODD'; const r=b.getBoundingClientRect(); return Math.round(r.x+r.width/2)+' '+Math.round(r.y+r.height/2);})()"
L ("dd: $rect")
if ($rect -eq 'NODD') { L "NO DROPDOWN - abort" } else {
  $p2 = $rect -split ' '; TapCss $p2[0] $p2[1]
  Start-Sleep -Seconds 1
  Shot '29-dd-clean'
  L ("expanded: " + (Ev "(()=>{const b=document.querySelector('button[data-component=home-projects-dropdown]'); return b?b.getAttribute('aria-expanded'):'gone';})()"))
  L ("MENU: " + (Ev "(()=>{const m=document.querySelectorAll('[role=menu],[role=listbox],[role=dialog]'); return JSON.stringify([...m].map(e=>e.innerText.trim().replace(/\n/g,'|').slice(0,200)));})()"))
  L ("4095 pos: " + (Ev "(()=>{const o=[];document.querySelectorAll('*').forEach(e=>{if(e.children.length===0&&/4095/.test(e.textContent||'')){const r=e.getBoundingClientRect();o.push(e.tagName+'@'+Math.round(r.x+r.width/2)+','+Math.round(r.y+r.height/2)+' w='+Math.round(r.width));}});return JSON.stringify(o.slice(0,8));})()"))
}
