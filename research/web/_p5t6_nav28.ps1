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
$SID = 'ses_eebaa2f6fffeBH8aBrsP0mnnv3'

L "=== NAV to session $SID ==="
L ("nav -> " + (Ev "(()=>{location.href='/server/$B64/session/$SID'; return 'OK';})()"))
Start-Sleep -Seconds 9
L ("url: " + (Ev "location.href"))
L ("body: " + (Ev "document.body.innerText.slice(0,400)"))
Shot '31-sess'
L ("composer: " + (Ev "JSON.stringify([...document.querySelectorAll('textarea,[contenteditable=true]')].map(e=>e.tagName+'|'+(e.placeholder||e.getAttribute('data-placeholder')||'').slice(0,30)))"))
L ("send btns: " + (Ev "JSON.stringify([...document.querySelectorAll('button')].map(b=>(b.getAttribute('aria-label')||b.innerText.trim()).replace(/\n/g,'|')).filter(Boolean).slice(-10))"))
