$ErrorActionPreference = 'Continue'
$root = 'C:\sensorsdata\main\program\opencode-android-shell'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s='emulator-5910'; $pkg='com.xiufeigo.opencodeshell.web.debug'; $act='com.xiufeigo.opencodeshell.web.WebHostActivity'
$out  = Join-Path $root 'research\web\_g2_ime3.txt'
Remove-Item $out -EA SilentlyContinue
function W($t){ Add-Content -Path $out -Value $t; Write-Output $t }
function Adb($a) { & $adb -s $s @a 2>&1 }
function Eval($js) {
  $one = ($js -replace "`r?`n",' ') -replace '\s{2,}',' '
  & $adb -s $s logcat -c
  & $adb -s $s shell "am start -n $pkg/$act --es oc_debug_eval '$one'" 2>&1 | Out-Null
  Start-Sleep -Milliseconds 1800
  $lc = (& $adb -s $s logcat -d 2>&1) | Select-String 'OcWebHost/Eval'
  if ($lc) { return ([string]($lc[-1]) -replace '^.*->\s*"','' -replace '"\s*$','') }
  return '<no eval result>'
}

W "################ GAP#4  IME overlap retry (round 3) ################"
W "Progress from round 2: shadow-piercing WORKS (found input[placeholder='Search sessions'] in shadow root,"
W "dpr=2.625). Home page '/' has NO composer -> must enter a session first."
W ""
W ("current url -> {0}" -f (Eval 'JSON.stringify({u:location.pathname})'))

W ""
W "=== [1] click a session row by its visible title (home list) ==="
$click = '(function(){var all=[];function w(r,d){if(d>14)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){all.push(a[i]);if(a[i].shadowRoot)w(a[i].shadowRoot,d+1)}}w(document,0);var cands=all.filter(function(e){var t=(e.textContent||"").trim();return /Counting from 1 to 4000|Count from 1 to 999/.test(t)&&e.children.length<6});if(!cands.length)return "no_session_row";var c=cands[0];var b=c.getBoundingClientRect();c.click();return JSON.stringify({tag:c.tagName,txt:(c.textContent||"").trim().slice(0,60),x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2)})})()'
W ("click -> {0}" -f (Eval $click))
Start-Sleep -Seconds 10
W ("after click -> {0}" -f (Eval 'JSON.stringify({u:location.pathname,ih:window.innerHeight})'))

W ""
W "=== [2] shadow-piercing composer search on the session page ==="
$find = '(function(){var out=[];function w(r,d){if(d>16)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||t==="input"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){var b=e.getBoundingClientRect();out.push({tag:t,ph:(e.getAttribute("placeholder")||e.getAttribute("data-placeholder")||"").slice(0,40),top:Math.round(b.top),bottom:Math.round(b.bottom),h:Math.round(b.height),cx:Math.round(b.left+b.width/2),cy:Math.round(b.top+b.height/2)})}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);return JSON.stringify({n:out.length,ih:window.innerHeight,items:out.slice(0,10)})})()'
$f = Eval $find
W ("search -> {0}" -f $f)

W ""
W "=== [3] REAL system tap on the composer ==="
$best = $null
if ($f -match '"items":\[(.*)\]\}') { $items = $Matches[1] }
$composer = $null
if ($f -match '\{"tag":"(textarea|input)","ph":"([^"]*)","top":(\d+),"bottom":(\d+),"h":(\d+),"cx":(\d+),"cy":(\d+)\}') {
  $ph = $Matches[2]
  if ($ph -notmatch 'Search') { $composer = [pscustomobject]@{tag=$Matches[1];ph=$ph;top=[int]$Matches[3];bottom=[int]$Matches[4];h=[int]$Matches[5];cx=[int]$Matches[6];cy=[int]$Matches[7]} }
}
if (-not $composer -and $f -match '"tag":"(textarea|input)","ph":"([^"]*)","top":(\d+),"bottom":(\d+),"h":(\d+),"cx":(\d+),"cy":(\d+)') {
  $composer = [pscustomobject]@{tag=$Matches[1];ph=$Matches[2];top=[int]$Matches[3];bottom=[int]$Matches[4];h=[int]$Matches[5];cx=[int]$Matches[6];cy=[int]$Matches[7]}
}
if ($composer) {
  W ("composer chosen: {0}" -f ($composer | ConvertTo-Json -Compress))
  W (">>> REAL TAP: input tap {0} {1} (system touch) <<<" -f $composer.cx, $composer.cy)
  Adb @('shell','input','tap',"$($composer.cx)","$($composer.cy)") | Out-Null
} else {
  W "composer NOT identified from search result"
}
Start-Sleep -Seconds 4

W ""
W "=== [4] ASSERT mInputShown BEFORE reading insets ==="
$shown='unknown'
for ($i=1;$i -le 8;$i++){
  $im = (Adb @('shell','dumpsys','input_method')) -join "`n"
  if ($im -match 'mInputShown=(\w+)') { $shown=$Matches[1] }
  W ("  attempt {0}: mInputShown = {1}" -f $i,$shown)
  if ($shown -eq 'true') { break }
  Start-Sleep -Seconds 2
}
W ("ASSERT mInputShown = {0}" -f $shown)

W ""
W "=== [5] focus via eval as a fallback, then re-assert ==="
$focus = '(function(){var f=null;function w(r,d){if(f||d>16)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){f=e;return}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);if(!f)return "NOT_FOUND";f.focus();return JSON.stringify({tag:f.tagName,active:document.activeElement===f})})()'
W ("focus -> {0}" -f (Eval $focus))
Start-Sleep -Seconds 3
$im = (Adb @('shell','dumpsys','input_method')) -join "`n"
if ($im -match 'mInputShown=(\w+)') { W ("mInputShown after eval-focus = {0}" -f $Matches[1]) }

W ""
W "=== [6] RAW dumpsys window insets : type=ime ==="
$ins = (Adb @('shell','dumpsys','window','insets')) -join "`n"
$ins | Set-Content (Join-Path $root 'research\web\_g2_ime_insets_raw.txt') -Encoding UTF8
$imel = ($ins -split "`r?`n") | Where-Object { $_ -match 'type=ime' }
if ($imel) { $imel | ForEach-Object { W ("RAW: " + $_.Trim()) } } else {
  W "RAW: <no 'type=ime' line in dumpsys window insets>"
  W "--- diagnosis: lines mentioning 'ime' ---"
  ($ins -split "`r?`n") | Where-Object { $_ -match 'ime' } | Select-Object -First 10 | ForEach-Object { W ("  REF: " + $_.Trim()) }
}
W ("type=ime line count = {0}" -f $imel.Count)

W ""
W "=== [7] composer bounds (final, with IME state) + overlap math inputs ==="
$bounds = '(function(){var f=null;function w(r,d){if(f||d>16)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){f=e;return}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);if(!f)return "NOT_FOUND";var b=f.getBoundingClientRect();var v=window.visualViewport;return JSON.stringify({tag:f.tagName,active:document.activeElement===f,top:Math.round(b.top),bottom:Math.round(b.bottom),h:Math.round(b.height),ih:window.innerHeight,dpr:window.devicePixelRatio,vvH:Math.round(v?v.height:0),vvOffTop:Math.round(v?v.offsetTop:0)})})()'
$b2 = Eval $bounds
W ("bounds -> {0}" -f $b2)
W ""
W "DONE"
