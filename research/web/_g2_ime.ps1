$ErrorActionPreference = 'Continue'
$root = 'C:\sensorsdata\main\program\opencode-android-shell'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s    = 'emulator-5910'
$pkg  = 'com.xiufeigo.opencodeshell.web.debug'
$act  = 'com.xiufeigo.opencodeshell.web.WebHostActivity'
$out  = Join-Path $root 'research\web\_g2_ime.txt'
Remove-Item $out -EA SilentlyContinue
function W($t){ $t | Tee-Object -FilePath $out -Append }
function Adb($a) { & $adb -s $s @a 2>&1 }
function Eval($js) {
  # collapse to ONE line (newlines break the device shell), then pass as a single
  # single-quoted token so parens/braces are not split by /system/bin/sh
  $one = ($js -replace "`r?`n", ' ') -replace '\s{2,}',' '
  & $adb -s $s logcat -c
  $remote = "am start -n $pkg/$act --es oc_debug_eval '$one'"
  & $adb -s $s shell $remote 2>&1 | Out-Null
  Start-Sleep -Milliseconds 1800
  $lc = (& $adb -s $s logcat -d -s 'OcWebHost/Eval:V' '*:S' 2>&1) -join "`n"
  $m = [regex]::Matches($lc, '->\s*"([\s\S]*?)"\s*$', 'Multiline')
  if ($m.Count) { return $m[$m.Count-1].Groups[1].Value } else { return "<no eval result captured>" }
}

W "################ GAP#4  IME overlap retry ################"
W "METHOD: pierce shadow roots to reach the composer (R2's failure cause), assert mInputShown=true BEFORE reading,"
W "        copy the RAW 'type=ime frame=' line, take composer getBoundingClientRect().bottom, compute overlap px."
W ""

Adb @('shell','am','force-stop',$pkg) | Out-Null
Start-Sleep -Seconds 2
Adb @('logcat','-c') | Out-Null
Adb @('shell','am','start','-n',"$pkg/$act") | Out-Null
Start-Sleep -Seconds 12
Adb @('logcat','-c') | Out-Null

W "=== [1] navigate into a session so a composer exists ==="
$nav = @'
(function(){try{var a=[].slice.call(document.querySelectorAll('a')).filter(function(x){return /\/session\//.test(x.getAttribute('href')||'')});if(a.length){a[0].click();return "clicked_session_link:"+a[0].getAttribute('href')}return "no_session_link"}catch(e){return "ERR:"+e.message}})()
'@
W ("nav -> {0}" -f (Eval $nav))
Start-Sleep -Seconds 8
Adb @('logcat','-c') | Out-Null

W ""
W "=== [2] shadow-piercing search for the composer (R2 got [] from top-level querySelectorAll) ==="
$find = @'
(function(){var out=[];function walk(root,d){if(d>14)return;var all=root.querySelectorAll('*');for(var i=0;i<all.length;i++){var el=all[i];var t=(el.tagName||'').toLowerCase();var ce=el.getAttribute?el.getAttribute('contenteditable'):null;var ph=el.getAttribute?el.getAttribute('placeholder'):null;var dp=el.getAttribute?el.getAttribute('data-placeholder'):null;if(t==='textarea'||(ce==='true'&&t!=='body')){var r=el.getBoundingClientRect();out.push({tag:t,ph:ph||dp||'',top:Math.round(r.top),bottom:Math.round(r.bottom),h:Math.round(r.height)})}if(el.shadowRoot)walk(el.shadowRoot,d+1)}}walk(document,0);return JSON.stringify({n:out.length,innerH:window.innerHeight,dpr:window.devicePixelRatio,items:out.slice(0,10)})})()
'@
$found = Eval $find
W ("composer search -> {0}" -f $found)

W ""
W "=== [3] focus the composer (real focus so the IME is actually raised) ==="
$focus = @'
(function(){var f=null;function walk(root,d){if(f||d>14)return;var all=root.querySelectorAll('*');for(var i=0;i<all.length;i++){var el=all[i];var t=(el.tagName||'').toLowerCase();if(t==='textarea'||(el.getAttribute&&el.getAttribute('contenteditable')==='true')){f=el;return}if(el.shadowRoot)walk(el.shadowRoot,d+1)}}walk(document,0);if(!f)return "NOT_FOUND";f.focus();return JSON.stringify({tag:f.tagName,active:document.activeElement===f})})()
'@
W ("focus -> {0}" -f (Eval $focus))
Start-Sleep -Seconds 3

W ""
W "=== [4] ALSO try a real user tap on the composer (system touch, not injection) ==="
$rect = Eval @'
(function(){var f=null;function walk(root,d){if(f||d>14)return;var all=root.querySelectorAll('*');for(var i=0;i<all.length;i++){var el=all[i];var t=(el.tagName||'').toLowerCase();if(t==='textarea'||(el.getAttribute&&el.getAttribute('contenteditable')==='true')){f=el;return}if(el.shadowRoot)walk(el.shadowRoot,d+1)}}walk(document,0);if(!f)return "NOT_FOUND";var r=f.getBoundingClientRect();var sx=window.innerWidth/Math.max(1,document.documentElement.clientWidth);return JSON.stringify({cx:Math.round((r.left+r.width/2)*sx),cy:Math.round((r.top+r.height/2)*sx),left:Math.round(r.left),right:Math.round(r.right),top:Math.round(r.top),bottom:Math.round(r.bottom),h:Math.round(r.height)})})()
'@
W ("composer rect -> {0}" -f $rect)
$cx = $null; $cy = $null
if ($rect -match '"cx":(\d+),"cy":(\d+)') { $cx=[int]$Matches[1]; $cy=[int]$Matches[2] }
if ($cx) {
  W (">>> REAL TAP: input tap {0} {1} (system touch) <<<" -f $cx, $cy)
  Adb @('shell','input','tap',"$cx","$cy") | Out-Null
  Start-Sleep -Seconds 3
}

W ""
W "=== [5] ASSERT mInputShown BEFORE reading insets (hard requirement) ==="
$shown = 'unknown'
for ($i=1; $i -le 10; $i++) {
  $im = (Adb @('shell','dumpsys','input_method')) -join "`n"
  if ($im -match 'mInputShown=(\w+)') { $shown = $Matches[1] }
  W ("  attempt {0}: mInputShown = {1}" -f $i, $shown)
  if ($shown -eq 'true') { break }
  Start-Sleep -Seconds 2
}
W ("ASSERT mInputShown = {0}" -f $shown)

W ""
W "=== [6] RAW dumpsys window insets lines for ime ==="
$ins = (Adb @('shell','dumpsys','window','insets')) -join "`n"
$ins | Set-Content (Join-Path $root 'research\web\_g2_ime_insets_raw.txt') -Encoding UTF8
$imeLines = ($ins -split "`r?`n") | Where-Object { $_ -match 'type=ime' }
if ($imeLines) { $imeLines | ForEach-Object { W ("RAW: " + $_.Trim()) } } else { W "RAW: <no 'type=ime' line found in dumpsys window insets>" }
W ("(total 'type=ime' line count = {0})" -f $imeLines.Count)
W ""
W "--- for reference, the first 3 insets lines that DO mention 'ime' anywhere ---"
($ins -split "`r?`n") | Where-Object { $_ -match 'ime' } | Select-Object -First 6 | ForEach-Object { W ("  REF: " + $_.Trim()) }

W ""
W "=== [7] composer bounds with IME state ==="
$bounds = Eval @'
(function(){var f=null;function walk(root,d){if(f||d>14)return;var all=root.querySelectorAll('*');for(var i=0;i<all.length;i++){var el=all[i];var t=(el.tagName||'').toLowerCase();if(t==='textarea'||(el.getAttribute&&el.getAttribute('contenteditable')==='true')){f=el;return}if(el.shadowRoot)walk(el.shadowRoot,d+1)}}walk(document,0);if(!f)return "NOT_FOUND";var r=f.getBoundingClientRect();return JSON.stringify({tag:f.tagName,active:document.activeElement===f,top:Math.round(r.top),bottom:Math.round(r.bottom),left:Math.round(r.left),right:Math.round(r.right),w:Math.round(r.width),h:Math.round(r.height),innerH:window.innerHeight,innerW:window.innerWidth,dpr:window.devicePixelRatio,vv:{w:Math.round(window.visualViewport?window.visualViewport.width:0),h:Math.round(window.visualViewport?window.visualViewport.height:0),offTop:Math.round(window.visualViewport?window.visualViewport.offsetTop:0)}})})()
'@
W ("bounds -> {0}" -f $bounds)

W ""
W "=== [8] overlap computation ==="
if ($imeLines.Count -gt 0 -and $bounds -match '"bottom":(\d+)') {
  $cb = [int]$Matches[1]
  $fl = ($imeLines[0] -replace '.*frame=\[?',' ')
  W ("composer bottom (CSS px, viewport coords) = {0}" -f $cb)
  W ("raw ime frame line: {0}" -f $imeLines[0].Trim())
} else {
  W ("CANNOT COMPUTE: imeLines={0}" -f $imeLines.Count)
}
W ""
W "DONE"
