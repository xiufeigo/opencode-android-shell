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

L "=== FILL + SUBMIT Add server ==="
# fill url / username / password via native setter + input event (React-friendly)
$fillJs = @"
(()=>{
  const set=(el,v)=>{const s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));};
  const byPh=p=>document.querySelector('input[placeholder='+JSON.stringify(p)+']');
  const u=byPh('http://localhost:4096'), n=byPh('Localhost'), p=byPh('password');
  if(!u||!n||!p) return 'MISS_FIELDS';
  set(u,'http://127.0.0.1:4095'); set(n,'opencode'); set(p,'opencode');
  return JSON.stringify({u:u.value,n:n.value,p:p.value?'*':''});
})()
"@
L ("fill: " + (Ev $fillJs))
Start-Sleep -Milliseconds 600
Shot '05-filled'

# submit button
L ("submit btns: " + (Ev "JSON.stringify([...document.querySelectorAll('button')].map(b=>b.innerText.trim()).filter(Boolean).slice(-8))"))
L ("click Add -> " + (Ev "(()=>{const b=[...document.querySelectorAll('button')].filter(b=>/^add$/i.test(b.innerText.trim())).pop(); if(!b)return 'NOTFOUND'; b.click(); return 'OK';})()"))
Shot '06-submitted'

# wait >= 14s for validation
L "waiting 16s for add validation..."
Start-Sleep -Seconds 16
L ("url: " + (Ev "location.href"))
L ("servers: " + (Ev "JSON.stringify([...document.querySelectorAll('button')].map(b=>b.innerText.trim().split('\n').slice(0,2).join('/')).filter(t=>/\d{4}/.test(t)))"))
Shot '07-added'
