$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S = 'emulator-5680'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
$SHOTS = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\p5t6_shots'
New-Item -ItemType Directory -Force -Path $SHOTS | Out-Null
$CDP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\ui1\ui1_cdp.ps1'
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\nav.log" -Value $s -Encoding utf8; Write-Host $s }
function Sh($c){ $o = & $adb -s $S shell $c 2>&1; return (($o | Out-String) -replace "`r`n",' | ') -replace '\s{2,}',' ' }
function Ev([string]$js){
  $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $CDP -Js $js -Port 9222 -Serial $S 2>&1
  $lines = ($o | Out-String) -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'VoidTaskResult|socket=' }
  return ($lines -join '')
}
function Shot($n){ & $adb -s $S shell "screencap -p /sdcard/$n.png" 2>&1|Out-Null; & $adb -s $S pull "/sdcard/$n.png" "$SHOTS\$n.png" 2>&1|Out-Null; L "shot $n" }

L "=== NAV (CDP-driven) ==="
# ensure drawer open
L ("drawer open? " + (Ev "[...document.querySelectorAll('button')].some(b=>b.innerText.trim()==='Settings')"))

# 1. Settings
L ("click Settings -> " + (Ev "(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='Settings'); if(!b)return 'NOTFOUND'; b.click(); return 'OK';})()"))
Start-Sleep -Seconds 2
L ("url: " + (Ev "location.href"))
Shot '02-settings'

# 2. find Server section
L ("sections: " + (Ev "JSON.stringify([...document.querySelectorAll('button')].map(b=>b.innerText.trim().split('\n')[0]).filter(Boolean).slice(0,20))"))
