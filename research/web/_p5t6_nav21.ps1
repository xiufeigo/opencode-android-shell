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
$PROJ = '69c97993067c761ba12913dd57c039089d5ee213'

L "=== ROUTE: /server/B64/PROJ ==="
L ("nav -> " + (Ev "(()=>{location.href='/server/$B64/$PROJ'; return 'OK';})()"))
Start-Sleep -Seconds 7
L ("url: " + (Ev "location.href"))
L ("body: " + (Ev "document.body.innerText.slice(0,500)"))
Shot '25-proj4095'
L ("session links: " + (Ev "JSON.stringify([...document.querySelectorAll('a[href*=session]')].map(a=>(a.getAttribute('href')||'')+'::'+a.innerText.trim().replace(/\n/g,'|')).slice(0,8))"))
