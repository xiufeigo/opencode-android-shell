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

L "=== REACHABILITY from page ==="
L ("fetch 4095: " + (Ev "(async()=>{try{const r=await fetch('http://127.0.0.1:4095/api/info',{headers:{authorization:'Basic '+btoa('opencode:opencode')}}); return 'status='+r.status+' body='+(await r.text()).slice(0,120);}catch(e){return 'ERR '+e}})()"))
L ("fetch 4173: " + (Ev "(async()=>{try{const r=await fetch('/api/info'); return 'status='+r.status;}catch(e){return 'ERR '+e}})()"))

L "=== LOGCAT tail (errors) ==="
$tail = Get-Content "$OUT\logcat_full.txt" -Tail 400 -ErrorAction SilentlyContinue
($tail | Where-Object { $_ -match 'oc-|OcWebHost|WebHostActivity|console|ERROR|FATAL|WebView' } | Select-Object -Last 30) -join "`n" | Write-Host
