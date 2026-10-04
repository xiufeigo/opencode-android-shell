$ErrorActionPreference = 'Continue'
$root = 'C:\sensorsdata\main\program\opencode-android-shell'
$adb  = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s='emulator-5910'; $pkg='com.xiufeigo.opencodeshell.web.debug'; $act='com.xiufeigo.opencodeshell.web.WebHostActivity'
$out  = Join-Path $root 'research\web\_g2_ime5.txt'
Remove-Item $out -EA SilentlyContinue
function W($t){ Add-Content -Path $out -Value $t; Write-Output $t }
function Adb($a) { & $adb -s $s @a 2>&1 }
function Eval($js) {
  $one = ($js -replace "`r?`n",' ') -replace '\s{2,}',' '
  & $adb -s $s logcat -c
  & $adb -s $s shell "am start -n $pkg/$act --es oc_debug_eval '$one'" 2>&1 | Out-Null
  Start-Sleep -Milliseconds 1800
  $lc = (& $adb -s $s logcat -d 2>&1) | Select-String 'OcWebHost/Eval'
  if ($lc) {
    $raw = [string]($lc[-1])
    $raw = $raw -replace '^.*->\s*"',''
    $raw = $raw -replace '"\s*$',''
    $raw = $raw -replace '\\"','"'
    return $raw
  }
  return '<no eval result>'
}

W "################ GAP#4  IME overlap retry (round 5, final) ################"
W "Fixes vs round 4: (a) unescape logcat's backslash-quotes before regex; (b) 'dumpsys window insets' is NOT a"
W "valid command on this build ('Bad window command') -> use 'dumpsys window' and filter for type=ime."
W ""
W ("start url -> {0}" -f (Eval 'JSON.stringify({u:location.pathname,ih:window.innerHeight})'))

W ""
W "=== [1] real tap on the session row (coordinates from round 4) ==="
$loc = '(function(){var all=[];function w(r,d){if(d>16)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){all.push(a[i]);if(a[i].shadowRoot)w(a[i].shadowRoot,d+1)}}w(document,0);var hits=all.filter(function(e){var t=(e.textContent||"").replace(/\s+/g," ").trim();return t==="Counting from 1 to 4000, one number per line"&&e.children.length===0});if(!hits.length)return "NO_EXACT_HIT";var b=hits[0].getBoundingClientRect();return JSON.stringify({tag:hits[0].tagName,x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2)})})()'
$l = Eval $loc
W ("locate -> {0}" -f $l)
if ($l -match '"x":(\d+),"y":(\d+)') {
  W (">>> REAL TAP on session row: input tap {0} {1} <<<" -f $Matches[1],$Matches[2])
  Adb @('shell','input','tap',$Matches[1],$Matches[2]) | Out-Null
} else { W "STILL no coordinates" }
Start-Sleep -Seconds 11
W ("after tap -> {0}" -f (Eval 'JSON.stringify({u:location.pathname,ih:window.innerHeight})'))

W ""
W "=== [2] shadow-piercing composer search ==="
$find = '(function(){var out=[];function w(r,d){if(d>16)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||t==="input"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){var b=e.getBoundingClientRect();out.push({tag:t,ph:(e.getAttribute("placeholder")||e.getAttribute("data-placeholder")||"").slice(0,44),top:Math.round(b.top),bottom:Math.round(b.bottom),h:Math.round(b.height),cx:Math.round(b.left+b.width/2),cy:Math.round(b.top+b.height/2)})}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);return JSON.stringify({n:out.length,ih:window.innerHeight,items:out.slice(0,10)})})()'
$f = Eval $find
W ("search -> {0}" -f $f)

W ""
W "=== [3] REAL tap on the composer ==="
$m = [regex]::Matches($f,'\{"tag":"(textarea|input)","ph":"([^"]*)","top":(\d+),"bottom":(\d+),"h":(\d+),"cx":(\d+),"cy":(\d+)\}')
$pick=$null
foreach ($x in $m) { if ($x.Groups[2].Value -notmatch 'Search') { $pick=$x; break } }
if (-not $pick -and $m.Count) { $pick=$m[$m.Count-1] }
if ($pick) {
  $cx=[int]$pick.Groups[6].Value; $cy=[int]$pick.Groups[7].Value
  W ("picked tag={0} ph='{1}'" -f $pick.Groups[1].Value,$pick.Groups[2].Value)
  W (">>> REAL TAP: input tap {0} {1} (system touch) <<<" -f $cx,$cy)
  Adb @('shell','input','tap',"$cx","$cy") | Out-Null
} else { W "no composer candidate" }
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
W "=== [5] valid insets source: 'dumpsys window' filtered for type=ime ==="
$win = (Adb @('shell','dumpsys','window')) -join "`n"
$win | Set-Content (Join-Path $root 'research\web\_g2_window_raw.txt') -Encoding UTF8
$imel = ($win -split "`r?`n") | Where-Object { $_ -match 'type=ime' }
if ($imel) { $imel | ForEach-Object { W ("RAW: " + $_.Trim()) } } else {
  W "RAW: <no 'type=ime' line in 'dumpsys window' either>"
  $types = [regex]::Matches(($win -replace "`r?`n",' '),'type=(\w+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
  W ("  insetsType values present: {0}" -f ($types -join ', '))
  W "  'ime' mentions in dump: {0}" -f ([regex]::Matches($win,'ime').Count)
}
W ("type=ime line count = {0}" -f $imel.Count)

W ""
W "=== [6] final composer bounds ==="
$bounds = '(function(){var f=null;function w(r,d){if(f||d>16)return;var a=r.querySelectorAll("*");for(var i=0;i<a.length;i++){var e=a[i];var t=(e.tagName||"").toLowerCase();if(t==="textarea"||(e.getAttribute&&e.getAttribute("contenteditable")==="true")){f=e;return}if(e.shadowRoot)w(e.shadowRoot,d+1)}}w(document,0);if(!f)return "NOT_FOUND";var b=f.getBoundingClientRect();var v=window.visualViewport;return JSON.stringify({tag:f.tagName,active:document.activeElement===f,top:Math.round(b.top),bottom:Math.round(b.bottom),h:Math.round(b.height),ih:window.innerHeight,dpr:window.devicePixelRatio,vvH:Math.round(v?v.height:0),vvOffTop:Math.round(v?v.offsetTop:0)})})()'
W ("bounds -> {0}" -f (Eval $bounds))
W ""
W "DONE"
