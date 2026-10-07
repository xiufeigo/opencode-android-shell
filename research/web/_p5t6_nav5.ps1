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

L "=== SUBMIT (last 'Add server') ==="
L ("vals: " + (Ev "JSON.stringify([...document.querySelectorAll('input')].map(i=>i.placeholder+'='+i.value))"))
L ("click -> " + (Ev "(()=>{const b=[...document.querySelectorAll('button')].filter(b=>/^add server$/i.test(b.innerText.trim())); if(!b.length)return 'NOTFOUND'; b[b.length-1].click(); return 'OK n='+b.length;})()"))
Shot '06-submitted'
L "waiting 16s for validation..."
Start-Sleep -Seconds 16
L ("url: " + (Ev "location.href"))
L ("all btns: " + (Ev "JSON.stringify([...document.querySelectorAll('button')].map(b=>b.innerText.trim().split('\n').slice(0,2).join('/')).filter(Boolean).slice(-14))"))
Shot '07-added'
