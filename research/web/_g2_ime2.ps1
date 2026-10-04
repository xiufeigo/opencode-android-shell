$ErrorActionPreference = 'Continue'
$root = 'C:\sensorsdata\main\program\opencode-android-shell'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s    = 'emulator-5910'
$pkg  = 'com.xiufeigo.opencodeshell.web.debug'
$act  = 'com.xiufeigo.opencodeshell.web.WebHostActivity'
$out  = Join-Path $root 'research\web\_g2_ime2.txt'
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

W "################ GAP#4  IME overlap retry (round 2) ################"
W "geometry 1080x2400@420; assert mInputShown=true BEFORE reading; copy RAW type=ime frame= line."
W ""
Adb @('shell','wm','size','1080x2400') | Out-Null
Adb @('shell','wm','density','420') | Out-Null
W ("wm size    : {0}" -f ((Adb @('shell','wm','size') | Out-String).Trim() -replace "`r?`n",' | '))
W ("wm density : {0}" -f ((Adb @('shell','wm','density') | Out-String).Trim() -replace "`r?`n",' | '))
Adb @('reverse','tcp:4095','tcp:4095') | Out-Null
W ("reverse    : {0}" -f ((Adb @('reverse','--list') | Out-String).Trim()))
W ""
W "=== install ==="
W ((Adb @('install','-r','-g',(Join-Path $root 'research\deliver\opencode-shell-web.apk'))) -join ' ')
Adb @('shell','am','force-stop',$pkg) | Out-Null
Start-Sleep -Seconds 2
Adb @('shell','am','start','-n',"$pkg/$act") | Out-Null
Start-Sleep -Seconds 14
W ("identity   : {0}" -f (Eval 'JSON.stringify({t:document.title,r:document.readyState,ih:window.innerHeight})'))

W ""
W "=== [1] open a session (so a composer exists) ==="
$nav = '(function(){var a=[].slice.call(document.querySelectorAll("a")).filter(function(x){return /session/.test(x.getAttribute("href")||"")});if(!a.length)return "no_session_link";a[0].click();return "clicked:"+a[0].getAttribute("href")})()'
W ("nav -> {0}" -f (Eval $nav))
Start-Sleep -Seconds 9
W ("after nav -> {0}" -f (Eval 'JSON.stringify({url:location.pathname,ih:window.innerHeight})'))

W ""
W "=== [2] shadow-piercing composer search (R2's querySelectorAll returned []) ==="
$find = '(function(){var out=[];function w(r,d){if(d>14)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||t==="input"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){var b=e.getBoundingClientRect();out.push({tag:t,ph:e.getAttribute("placeholder")||e.getAttribute("data-placeholder")||"",top:Math.round(b.top),bottom:Math.round(b.bottom),h:Math.round(b.height)})}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);return JSON.stringify({n:out.length,ih:window.innerHeight,dpr:window.devicePixelRatio,items:out.slice(0,8)})})()'
W ("search -> {0}" -f (Eval $find))

W ""
W "=== [3] focus composer (raises IME) ==="
$focus = '(function(){var f=null;function w(r,d){if(f||d>14)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){f=e;return}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);if(!f)return "NOT_FOUND";f.focus();return JSON.stringify({tag:f.tagName,active:document.activeElement===f})})()'
W ("focus -> {0}" -f (Eval $focus))

W ""
W "=== [4] REAL system tap on the composer ==="
$rect = '(function(){var f=null;function w(r,d){if(f||d>14)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){f=e;return}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);if(!f)return "NOT_FOUND";var b=f.getBoundingClientRect();return JSON.stringify({cx:Math.round(b.left+b.width/2),cy:Math.round(b.top+b.height/2),top:Math.round(b.top),bottom:Math.round(b.bottom),h:Math.round(b.height)})})()'
$r = Eval $rect
W ("rect -> {0}" -f $r)
if ($r -match '"cx":(\d+),"cy":(\d+)') {
  W (">>> REAL TAP: input tap {0} {1} <<<" -f $Matches[1], $Matches[2])
  Adb @('shell','input','tap',$Matches[1],$Matches[2]) | Out-Null
  Start-Sleep -Seconds 4
}

W ""
W "=== [5] ASSERT mInputShown BEFORE reading insets ==="
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
W "=== [6] RAW dumpsys window insets: type=ime ==="
$ins = (Adb @('shell','dumpsys','window','insets')) -join "`n"
$ins | Set-Content (Join-Path $root 'research\web\_g2_ime_insets_raw.txt') -Encoding UTF8
$imel = ($ins -split "`r?`n") | Where-Object { $_ -match 'type=ime' }
if ($imel) { $imel | ForEach-Object { W ("RAW: " + $_.Trim()) } } else {
  W "RAW: <no 'type=ime' line in dumpsys window insets>"
  W "--- any line mentioning ime (for diagnosis) ---"
  ($ins -split "`r?`n") | Where-Object { $_ -match 'ime' } | Select-Object -First 8 | ForEach-Object { W ("  REF: " + $_.Trim()) }
}
W ("type=ime line count = {0}" -f $imel.Count)

W ""
W "=== [7] composer bounds + visualViewport ==="
$bounds = '(function(){var f=null;function w(r,d){if(f||d>14)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){f=e;return}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);if(!f)return "NOT_FOUND";var b=f.getBoundingClientRect();var v=window.visualViewport;return JSON.stringify({tag:f.tagName,active:document.activeElement===f,top:Math.round(b.top),bottom:Math.round(b.bottom),h:Math.round(b.height),ih:window.innerHeight,dpr:window.devicePixelRatio,vvH:Math.round(v?v.height:0),vvOffTop:Math.round(v?v.offsetTop:0)})})()'
W ("bounds -> {0}" -f (Eval $bounds))
W ""
W "DONE"
