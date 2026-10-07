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

L "=== CLICK Server tab (real click) ==="
L ("url: " + (Ev "location.href"))
L ("click Server -> " + (Ev "(()=>{const b=[...document.querySelectorAll('button')].filter(b=>b.innerText.trim().split('\n')[0]==='Server'); if(!b.length)return 'NOTFOUND'; b[b.length-1].click(); return 'OK';})()"))
Start-Sleep -Seconds 3
L ("url: " + (Ev "location.href"))
L ("BODY: " + (Ev "(()=>{const t=document.body.innerText; const i=t.indexOf('Server'); return t.slice(i, i+900)})()"))
Shot '22-server-tab'
L ("CLICKABLES: " + (Ev "JSON.stringify([...document.querySelectorAll('button,a,[role=menuitem]')].map(e=>{const r=e.getBoundingClientRect();return e.tagName+'['+e.innerText.trim().replace(/\n/g,'|').slice(0,26)+']@'+Math.round(r.x)+','+Math.round(r.y)}).filter(s=>/4095|4173|default|switch|use|open|set/i.test(s)))"))
