$ErrorActionPreference = 'Continue'
$root = 'C:\sensorsdata\main\program\opencode-android-shell'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s='emulator-5910'; $pkg='com.xiufeigo.opencodeshell.web.debug'; $act='com.xiufeigo.opencodeshell.web.WebHostActivity'
$out  = Join-Path $root 'research\web\_g2_ime4.txt'
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

W "################ GAP#4  IME overlap retry (round 4) ################"
W "round 3 lesson: clicking the <html> ancestor did nothing. This round: find the node whose own text"
W "EXACTLY equals a session title, then issue a REAL system tap at its centre (not a JS .click())."
W ""
W ("start url -> {0}" -f (Eval 'JSON.stringify({u:location.pathname,ih:window.innerHeight})'))

W ""
W "=== [1] locate a session row by EXACT textContent ==="
$loc = '(function(){var all=[];function w(r,d){if(d>16)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){all.push(a[i]);if(a[i].shadowRoot)w(a[i].shadowRoot,d+1)}}w(document,0);var hits=all.filter(function(e){var t=(e.textContent||"").replace(/\s+/g," ").trim();return (t==="Counting from 1 to 4000, one number per line"||t==="Count from 1 to 999, one number per line")});if(!hits.length)return "NO_EXACT_HIT";var out=hits.slice(0,4).map(function(e){var b=e.getBoundingClientRect();return {tag:e.tagName,kids:e.children.length,x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2),w:Math.round(b.width),h:Math.round(b.height)}});return JSON.stringify({n:hits.length,items:out})})()'
$loc2 = Eval $loc
W ("locate -> {0}" -f $loc2)

$tx=$null; $ty=$null
if ($loc2 -match '"x":(\d+),"y":(\d+)') { $tx=[int]$Matches[1]; $ty=[int]$Matches[2] }
if ($tx) {
  W (">>> REAL TAP on session row: input tap {0} {1} <<<" -f $tx,$ty)
  Adb @('shell','input','tap',"$tx","$ty") | Out-Null
} else { W "no coordinates -> falling back to JS click on deepest exact-match node" }
Start-Sleep -Seconds 10
W ("after tap -> {0}" -f (Eval 'JSON.stringify({u:location.pathname,ih:window.innerHeight})'))

W ""
W "=== [2] shadow-piercing composer search ==="
$find = '(function(){var out=[];function w(r,d){if(d>16)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||t==="input"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){var b=e.getBoundingClientRect();out.push({tag:t,ph:(e.getAttribute("placeholder")||e.getAttribute("data-placeholder")||"").slice(0,44),top:Math.round(b.top),bottom:Math.round(b.bottom),h:Math.round(b.height),cx:Math.round(b.left+b.width/2),cy:Math.round(b.top+b.height/2)})}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);return JSON.stringify({n:out.length,ih:window.innerHeight,items:out.slice(0,10)})})()'
$f = Eval $find
W ("search -> {0}" -f $f)

W ""
W "=== [3] pick the composer (textarea/contenteditable, else lowest non-Search input) and REAL tap ==="
$cx=$null;$cy=$null
$m = [regex]::Matches($f,'\{"tag":"(textarea|input)","ph":"([^"]*)","top":(\d+),"bottom":(\d+),"h":(\d+),"cx":(\d+),"cy":(\d+)\}')
$pick = $null
foreach ($x in $m) { if ($x.Groups[2].Value -notmatch 'Search') { $pick = $x; break } }
if (-not $pick -and $m.Count) { $pick = $m[$m.Count-1] }
if ($pick) {
  $tag=$pick.Groups[1].Value; $ph=$pick.Groups[2].Value; $cx=[int]$pick.Groups[6].Value; $cy=[int]$pick.Groups[7].Value
  W ("picked: tag={0} ph='{1}' tap=({2},{3})" -f $tag,$ph,$cx,$cy)
  W (">>> REAL TAP: input tap {0} {1} (system touch) <<<" -f $cx,$cy)
  Adb @('shell','input','tap',"$cx","$cy") | Out-Null
} else { W "no composer candidate found" }
Start-Sleep -Seconds 4

W ""
W "=== [4] ASSERT mInputShown BEFORE reading insets ==="
$shown='unknown'
for ($i=1;$i -le 6;$i++){
  $im = (Adb @('shell','dumpsys','input_method')) -join "`n"
  if ($im -match 'mInputShown=(\w+)') { $shown=$Matches[1] }
  W ("  attempt {0}: mInputShown = {1}" -f $i,$shown)
  if ($shown -eq 'true') { break }
  Start-Sleep -Seconds 2
}
W ("ASSERT mInputShown = {0}" -f $shown)

W ""
W "=== [5] eval focus fallback + re-assert ==="
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
  W "RAW: <no 'type=ime' line present in dumpsys window insets>"
  W "--- insetsType values actually present ---"
  $types = [regex]::Matches(($ins -replace "`r?`n",' '),'type=(\w+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
  W ("  types seen: {0}" -f ($types -join ', '))
  W "--- first 12 raw lines of the insets dump ---"
  ($ins -split "`r?`n") | Select-Object -First 12 | ForEach-Object { W ("  " + $_.Trim()) }
}
W ("type=ime line count = {0}" -f $imel.Count)

W ""
W "=== [7] final composer bounds + overlap inputs ==="
$bounds = '(function(){var f=null;function w(r,d){if(f||d>16)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){f=e;return}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);if(!f)return "NOT_FOUND";var b=f.getBoundingClientRect();var v=window.visualViewport;return JSON.stringify({tag:f.tagName,active:document.activeElement===f,top:Math.round(b.top),bottom:Math.round(b.bottom),h:Math.round(b.height),ih:window.innerHeight,dpr:window.devicePixelRatio,vvH:Math.round(v?v.height:0),vvOffTop:Math.round(v?v.offsetTop:0)})})()'
W ("bounds -> {0}" -f (Eval $bounds))
W ""
W "DONE"
