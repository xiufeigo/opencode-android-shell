$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S = 'emulator-5680'
$PKG = 'com.xiufeigo.opencodeshell.web.debug'
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

L "=== GRANT NOTIF PERM ==="
L ("grant: " + (Sh "pm grant $PKG android.permission.POST_NOTIFICATIONS"))
L ("perm: " + (Sh "dumpsys package $PKG | grep -i POST_NOTIFICATIONS"))
L ("canPost: " + (Sh "cmd notification allowed_assistant 2>/dev/null; dumpsys package $PKG | grep -A2 POST_NOTIFICATIONS | head -6"))

L "=== SWITCH TO 4095 ==="
# go home first
L ("go home -> " + (Ev "(()=>{const b=[...document.querySelectorAll('button')].find(b=>(b.getAttribute('aria-label')||'')==='Tabs'); if(!b)return 'NOTFOUND'; b.click(); return 'OK';})()"))
Start-Sleep -Seconds 2
L ("url: " + (Ev "location.href"))
# open projects dropdown
L ("open dropdown -> " + (Ev "(()=>{const b=document.querySelector('[data-component=home-projects-dropdown],button[aria-haspopup=dialog]'); if(!b)return 'NOTFOUND'; b.click(); return 'OK expanded='+b.getAttribute('aria-expanded');})()"))
Start-Sleep -Seconds 2
Shot '08-dropdown'
L ("items: " + (Ev "JSON.stringify([...document.querySelectorAll('[role=menuitem],a,button')].map(e=>e.innerText.trim().split('\n')[0]).filter(t=>/4095|4173/.test(t)))"))
L ("click 4095 -> " + (Ev "(()=>{const e=[...document.querySelectorAll('[role=menuitem],a,button')].find(e=>/4095/.test(e.innerText)); if(!e)return 'NOTFOUND'; e.click(); return 'OK';})()"))
Start-Sleep -Seconds 3
L ("url: " + (Ev "location.href"))
Shot '09-ctx4095'
