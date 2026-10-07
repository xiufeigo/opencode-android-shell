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

L "=== BACK TO HOME + projects dropdown ==="
L ("nav home -> " + (Ev "(()=>{location.href='/'; return 'OK';})()"))
Start-Sleep -Seconds 4
L ("url: " + (Ev "location.href"))
L ("h1: " + (Ev "document.querySelector('h1')?.innerText"))
L ("open projects dd -> " + (Ev "(()=>{const b=document.querySelector('button[data-component=home-projects-dropdown]'); if(!b)return 'NOTFOUND'; b.click(); return 'OK exp='+b.getAttribute('aria-expanded');})()"))
Start-Sleep -Seconds 2
L ("menu items: " + (Ev "JSON.stringify([...document.querySelectorAll('a,[role=menuitem],button')].map(e=>e.getAttribute('href')||e.innerText.trim().split('\n')[0]).filter(t=>/4095|4173/.test(t)))"))
Shot '12-dd'
L ("click 4095 -> " + (Ev "(()=>{const e=[...document.querySelectorAll('a')].find(a=>/4095/.test(a.getAttribute('href')||a.innerText)); if(!e)return 'NOTFOUND'; e.click(); return 'OK href='+e.getAttribute('href');})()"))
Start-Sleep -Seconds 5
L ("url: " + (Ev "location.href"))
L ("h1: " + (Ev "document.querySelector('h1')?.innerText"))
Shot '13-ctx4095'
L ("sessions: " + (Ev "JSON.stringify([...document.querySelectorAll('a[href*=session]')].map(a=>a.innerText.trim().split('\n')[0]).slice(0,8))"))
