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

$B64 = 'aHR0cDovLzEyNy4wLjAuMTo0MDk1'   # http://127.0.0.1:4095
L "=== DIRECT NAV to 4095 context ==="
L ("nav -> " + (Ev "(()=>{location.href='/server/$B64/'; return 'OK';})()"))
Start-Sleep -Seconds 5
L ("url: " + (Ev "location.href"))
L ("title: " + (Ev "document.querySelector('h1')?.innerText"))
Shot '10-home4095'
# open projects dropdown on home
L ("open dropdown -> " + (Ev "(()=>{const b=document.querySelector('button[aria-haspopup=dialog]'); if(!b)return 'NOTFOUND'; b.click(); return 'OK';})()"))
Start-Sleep -Seconds 2
L ("items: " + (Ev "JSON.stringify([...document.querySelectorAll('a,button,[role=menuitem]')].map(e=>e.innerText.trim().split('\n')[0]).filter(t=>/4095|4173/.test(t)))"))
Shot '11-dropdown-home'
