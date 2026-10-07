$ErrorActionPreference = 'Continue'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$S = 'emulator-5680'
$PKG = 'com.xiufeigo.opencodeshell.web.debug'
$OUT = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\out_p5t6'
$SHOTS = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\p5t6_shots'
$CDP = 'D:\XiaoZhuTouTou\opencode-android-shell\research\web\ui1\ui1_cdp.ps1'
$LOG = "$OUT\logcat_full.txt"
function L($m){ $s = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss.fff'), $m; Add-Content -Path "$OUT\t6.log" -Value $s -Encoding utf8; Write-Host $s }
function Sh($c){ $o = & $adb -s $S shell $c 2>&1; return (($o | Out-String) -replace "`r`n",' | ') -replace '\s{2,}',' ' }
function Ev([string]$js){
  $o = & powershell -NoProfile -ExecutionPolicy Bypass -File $CDP -Js $js -Port 9222 -Serial $S 2>&1
  return ((($o | Out-String) -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'VoidTaskResult|socket=' }) -join '')
}
function Shot($n){ & $adb -s $S shell "screencap -p /sdcard/$n.png" 2>&1|Out-Null; & $adb -s $S pull "/sdcard/$n.png" "$SHOTS\$n.png" 2>&1|Out-Null; L "shot $n" }
function MarkLog($tag){ $sz=(Get-Item $LOG).Length; L "MARK $tag bytes=$sz devtime=$(Sh 'date +%H:%M:%S')" }

L "=== T6 TEST START ==="
L ("logcat pid alive: " + ((Get-Process -Id (Get-Content "$OUT\logcat.pid") -ErrorAction SilentlyContinue) -ne $null))
L ("url: " + (Ev "location.href"))
L ("fg before: " + (Sh "dumpsys activity activities | grep -m1 'topResumedActivity'"))
MarkLog 'PRE-SEND'

# ---------- TYPE ----------
$MSG = 'Count from 1 to 200 comma separated'
$typeJs = @"
(()=>{
 const el=document.querySelector('div[contenteditable=true]');
 if(!el) return 'NO_COMPOSER';
 el.focus();
 const r=document.createRange(); r.selectNodeContents(el); r.deleteContents();
 const tn=document.createTextNode('$MSG');
 r.insertNode(tn); r.setStartAfter(tn); r.collapse(true);
 const s=getSelection(); s.removeAllRanges(); s.addRange(r);
 el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'$MSG'}));
 el.dispatchEvent(new Event('change',{bubbles:true}));
 return 'TYPED len='+el.innerText.length;
})()
"@
L ("type: " + (Ev $typeJs))
Start-Sleep -Milliseconds 800
Shot '32-typed'
L ("composer text: " + (Ev "(()=>{const e=document.querySelector('div[contenteditable=true]'); return e?e.innerText.slice(0,80):'none'})()"))

# ---------- SEND ----------
$t0 = Get-Date
L "SEND click at $(Get-Date -Format 'HH:mm:ss.fff')"
$sendJs = "(()=>{const b=[...document.querySelectorAll('button')].find(b=>/^send$/i.test((b.getAttribute('aria-label')||b.innerText).trim())); if(!b)return 'NOSEND'; b.click(); return 'SENT disabled='+b.disabled;})()"
L ("send: " + (Ev $sendJs))

# ---------- VERIFY ROUND START (max 1.2s) then HOME ----------
$deadline = (Get-Date).AddMilliseconds(1200)
$roundStarted = $false
while ((Get-Date) -lt $deadline) {
  $sz = (Get-Item $LOG).Length
  $chunk = Get-Content $LOG -Tail 400 -ErrorAction SilentlyContinue
  if ($chunk | Where-Object { $_ -match 'round POST|__post__|keepScreenOn -> true|api/session/.*/prompt' }) { $roundStarted = $true; break }
  Start-Sleep -Milliseconds 120
}
L ("roundStarted(before HOME) = $roundStarted ; elapsed = " + [int]((Get-Date)-$t0).TotalMilliseconds + "ms")
MarkLog 'PRE-HOME'
$t1 = Get-Date
L "HOME at $(Get-Date -Format 'HH:mm:ss.fff')"
Sh "input keyevent 3" | Out-Null
L ("send->HOME latency = " + [int]($t1-$t0).TotalMilliseconds + "ms")
MarkLog 'POST-HOME'
Shot '33-home'

# ---------- WAIT 45s ----------
L "waiting 45s in background..."
$bg = @()
foreach ($i in 1..9) {
  Start-Sleep -Seconds 5
  $n = (Sh "dumpsys notification --noredact | grep -c $PKG")
  $bg += "t+$($i*5)s notif_hits=$n"
}
L ($bg -join ' | ')
MarkLog 'BG45'
Shot '34-bg45'
L ("notif dump at bg: " + (Sh "dumpsys notification --noredact | grep $PKG | head -20"))
L "=== T6 BACKGROUND PHASE DONE ==="
