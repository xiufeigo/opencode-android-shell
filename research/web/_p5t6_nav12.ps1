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

L "=== RESTART APP (re-read server store) ==="
L ("stop: " + (Sh "am force-stop $PKG"))
Start-Sleep -Seconds 2
L ("start: " + (Sh "am start -n $ACT"))
Start-Sleep -Seconds 10
$p = (Sh "pidof $PKG")
L ("pid: " + $p)
& $adb -s $S forward --remove-all 2>&1 | Out-Null
$unix = (Sh "cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1")
& $adb -s $S forward tcp:9222 "localabstract:$unix" 2>&1 | Out-Null
L ("devtools: $unix")
Start-Sleep -Seconds 4
L ("url: " + (Ev "location.href"))
L ("body: " + (Ev "document.body.innerText.slice(0,400)"))
Shot '18-after-restart'
L ("dd -> " + (Ev "(()=>{const b=document.querySelector('button[data-component=home-projects-dropdown]'); if(!b)return 'NODD'; b.click(); return 'OK';})()"))
Start-Sleep -Seconds 2
L ("items: " + (Ev "JSON.stringify([...document.querySelectorAll('a,button,[role=menuitem]')].map(e=>(e.getAttribute('href')||'')+'::'+e.innerText.trim().replace(/\n/g,'|')).filter(t=>/4095|4173/.test(t)))"))
Shot '19-dd'
