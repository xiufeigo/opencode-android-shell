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
$DPR = 2.625
function TapCss($x,$y){ $dx=[int]($x*$DPR); $dy=[int]($y*$DPR); Sh "input tap $dx $dy" | Out-Null; Start-Sleep -Milliseconds 1100 }

L "=== SERVER TAB via real taps, dump ALL rows ==="
Sh "input keyevent 4" | Out-Null; Start-Sleep -Milliseconds 600
Sh "input tap 532 121" | Out-Null; Start-Sleep -Seconds 2
L ("drawer: " + (Ev "(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='Settings'); if(!b)return 'NOSET'; const r=b.getBoundingClientRect(); return Math.round(r.x+r.width/2)+','+Math.round(r.y+r.height/2);})()"))
$r = Ev "(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.innerText.trim()==='Settings'); const r=b.getBoundingClientRect(); return Math.round(r.x+r.width/2)+' '+Math.round(r.y+r.height/2);})()"
if ($r -notmatch 'undefined') { $p=$r -split ' '; TapCss $p[0] $p[1] }
Start-Sleep -Seconds 2
L ("url: " + (Ev "location.href"))
# click Server in sidebar
$srv = Ev "(()=>{const bs=[...document.querySelectorAll('button')].filter(b=>b.innerText.trim().split('\n')[0]==='Server'); if(!bs.length)return 'NONE'; const b=bs[bs.length-1]; const r=b.getBoundingClientRect(); return Math.round(r.x+r.width/2)+' '+Math.round(r.y+r.height/2);})()"
L ("server tab pos: $srv")
if ($srv -ne 'NONE') { $p=$srv -split ' '; TapCss $p[0] $p[1] }
Start-Sleep -Seconds 3
Shot '26-server-tab'
L ("url: " + (Ev "location.href"))
L ("ALL ROWS: " + (Ev "JSON.stringify([...document.querySelectorAll('button,a')].map(e=>{const r=e.getBoundingClientRect();return e.tagName+'['+e.innerText.trim().replace(/\n/g,'|').slice(0,30)+']@'+Math.round(r.x+r.width/2)+','+Math.round(r.y+r.height/2)+' vis='+(r.width>0)}).filter(s=>/4095|4173|server|default/i.test(s)))"))
