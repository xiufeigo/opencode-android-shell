$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S = 'emulator-5680'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
$CDP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\ui1\ui1_cdp.ps1'
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\nav.log" -Value $s -Encoding utf8; Write-Host $s }
function Sh($c){ $o = & $adb -s $S shell $c 2>&1; return (($o | Out-String) -replace "`r`n",' | ') -replace '\s{2,}',' ' }
function Ev([string]$js){
  $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $CDP -Js $js -Port 9222 -Serial $S 2>&1
  return ((($o | Out-String) -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'VoidTaskResult|socket=' }) -join '')
}

L "=== STORE STATE AFTER RESTART ==="
L ("server store: " + (Ev "(()=>{try{return localStorage.getItem('opencode.global.dat:server')}catch(e){return 'ERR '+e}})()"))
L ("keys: " + (Ev "JSON.stringify(Object.keys(localStorage).filter(k=>/server|project|recent|tab/i.test(k)))"))

L "=== LOGCAT: route error ==="
$t = Get-Content "$OUT\logcat_full.txt" -Tail 2500 -ErrorAction SilentlyContinue
($t | Where-Object { $_ -match 'console|ERROR|Exception|Something went wrong|unhandled' } | Select-Object -Last 20) -join "`n" | Write-Host

L "=== CLIENT-SIDE nav (no reload) to 4095 ==="
L ("pushState -> " + (Ev "(()=>{try{history.pushState({},'','/server/aHR0cDovLzEyNy4wLjAuMTo0MDk1/'); window.dispatchEvent(new PopStateEvent('popstate')); return 'OK';}catch(e){return 'ERR '+e}})()"))
Start-Sleep -Seconds 5
L ("url: " + (Ev "location.href"))
L ("body: " + (Ev "document.body.innerText.slice(0,400)"))
