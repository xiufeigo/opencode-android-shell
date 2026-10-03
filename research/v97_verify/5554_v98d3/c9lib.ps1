# c9lib.ps1 - round-9 (COVERAGE9) helper lib. No BACK(4), no ESC(111). device=emulator-5554 only.
$ErrorActionPreference='Continue'
$ADB="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$DEV="emulator-5554"
$DIR="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
$PKG="com.xiufeigo.opencodeshell"
$SD="/sdcard/d3r9"

function A { & $ADB -s $DEV @args 2>&1 }

function Log([string]$m){
  $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m
  Add-Content -Path "$DIR\c9_console.log" -Value $line -Encoding UTF8
}

function Focus {
  $o = & $ADB -s $DEV shell dumpsys window 2>$null | Select-String "mCurrentFocus" | Select-Object -First 1
  if($o -and $o -match 'mCurrentFocus=(\S+)'){ return $matches[1] }
  return "?"
}

function ImeOff {
  & $ADB -s $DEV shell ime disable com.android.inputmethod.latin 2>&1 | Out-Null
  return "ime-disabled"
}

# ---- dump + screenshot, return parsed xml ----
function Dump([string]$tag){
  & $ADB -s $DEV shell "mkdir -p $SD" 2>&1 | Out-Null
  & $ADB -s $DEV shell "uiautomator dump --compressed $SD/$tag.xml" 2>&1 | Out-Null
  & $ADB -s $DEV shell "screencap -p $SD/$tag.png" 2>&1 | Out-Null
  & $ADB -s $DEV pull "$SD/$tag.xml" "$DIR\$tag.xml" 2>&1 | Out-Null
  & $ADB -s $DEV pull "$SD/$tag.png" "$DIR\$tag.png" 2>&1 | Out-Null
  if(Test-Path "$DIR\$tag.xml"){ return [xml](Get-Content "$DIR\$tag.xml" -Raw -Encoding UTF8) }
  return $null
}

# ---- parse nodes robustly: matches text="x" and text='x' ----
function Nodes($x){
  if(-not $x){ return @() }
  $out=@()
  foreach($n in $x.SelectNodes("//node")){
    $rec=[ordered]@{
      text=$n.text; cd=$n.'content-desc'; cls=$n.class
      b=$n.bounds; clk=$n.clickable; en=$n.enabled
      rid=$n.'resource-id'; pkg=$n.package; sel=$n.selected
    }
    $o=[pscustomobject]$rec
    $out+=$o
  }
  return $out
}
function Lbl($n){ if($n.text -and $n.text -ne ''){ return $n.text }; if($n.cd -and $n.cd -ne ''){ return $n.cd }; return "" }

function Bounds($n){
  $raw = $n.b
  if(-not $raw){ $raw = $n.bounds }
  if(-not $raw){ return $null }
  $m=[regex]::Matches($raw,'-?\d+') | ForEach-Object { [int]$_.Value }
  if($m.Count -lt 4){ return $null }
  return @{x1=$m[0];y1=$m[1];x2=$m[2];y2=$m[3];cx=[int](($m[0]+$m[2])/2);cy=[int](($m[1]+$m[3])/2)}
}
function BStr($n){ $b=Bounds $n; if(-not $b){return "?"}; return "[$($b.x1),$($b.y1)][$($b.x2),$($b.y2)]" }

function FindTxt($x,[string]$t){
  return @((Nodes $x) | Where-Object { $_.text -eq $t -or $_.cd -eq $t })
}
function FindLike($x,[string]$pat){
  return @((Nodes $x) | Where-Object { (Lbl $_) -match $pat })
}

function TapLbl($x,[string]$t,[int]$i=0,[int]$sleep=1200){
  $h=(FindTxt $x $t)[$i]
  if(-not $h){ Log "  TAP-MISS '$t'"; return $false }
  $b=Bounds $h
  & $ADB -s $DEV shell input tap $b.cx $b.cy 2>&1 | Out-Null
  Log ("  TAP '{0}' i={1} {2} -> {3},{4}" -f $t,$i,(BStr $h),$b.cx,$b.cy)
  Start-Sleep -Milliseconds $sleep
  return $true
}
function TapAt($x,[string]$t,[int]$i=0,[int]$sleep=1200){ return TapLbl $x $t $i $sleep }

function TapXY([int]$cx,[int]$cy,[int]$sleep=1000,[string]$why=""){
  & $ADB -s $DEV shell input tap $cx $cy 2>&1 | Out-Null
  Log ("  TAPXY {0},{1} {2}" -f $cx,$cy,$why)
  Start-Sleep -Milliseconds $sleep
}

function TypeTxt([string]$s){
  $esc = $s -replace '([ &;<>\|\\"''\(\)\{\}\[\]\$\^\*\+\?\~`\/])','\$1'
  $esc = $esc -replace ' ','%s'
  & $ADB -s $DEV shell input text "$esc" 2>&1 | Out-Null
  Log "  TYPE '$s'"
}

# clear a field: tap it, MOVE_END, then many DEL
function ClearField($node){
  $b=Bounds $node
  & $ADB -s $DEV shell input tap $b.cx $b.cy 2>&1 | Out-Null
  Start-Sleep -Milliseconds 600
  & $ADB -s $DEV shell input keyevent KEYCODE_MOVE_END 2>&1 | Out-Null
  for($i=0;$i -lt 60;$i++){ & $ADB -s $DEV shell input keyevent 67 2>&1 | Out-Null }
  Log "  CLEAR field at $($b.cx),$($b.cy)"
  Start-Sleep -Milliseconds 300
}

# ---- server row inventory ----
# R9 CRITICAL FIX: the address (127.0.0.1:2222 / 10.0.2.2:4095) is NOT rendered in the
# UI dump at all. A row is identified by its NAME TextView. R8 keyed rows by address and
# therefore always reported "(row not visible)" + kebabs=0. Rows are keyed by name now.
function RowLines($x){
  $out=@()
  foreach($n in (Nodes $x)){
    if($n.cls -ne 'android.widget.TextView'){ continue }
    $l = Lbl $n
    if(-not $l -or $l -eq ''){ continue }
    if($l -match '^(Reconnect|Disconnect|Add SSH server|SSH tunnels|Servers|Shell|About|Experimental|Extensions|Home|Settings|Help|New session|Add project|Command palette)$'){ continue }
    $b = Bounds $n
    if(-not $b){ continue }
    if($b.x1 -ge 180 -and $b.x1 -le 260 -and ($b.x2-$b.x1) -lt 420){
      $out+=[pscustomobject]@{addr=$l; name=$l; node=$n; b=(BStr $n); cx=$b.cx; cy=$b.cy}
    }
  }
  return $out
}
function RowSummary($x){
  $r=RowLines $x
  $s=@()
  foreach($it in $r){ $s+=("{0} {1}" -f $it.name,$it.b) }
  return ($s -join " | ")
}
function RowCount($x){ return (RowLines $x).Count }
function FindRow($x,[string]$name){ return @((RowLines $x) | Where-Object { $_.name -eq $name }) }
function OtherRow($x){ return @((RowLines $x) | Where-Object { $_.name -eq 'chk123' }) }

# status text directly under a row name (y within ~70px below, same left column)
function RowStatus($x,[string]$name){
  $r=(FindRow $x $name)[0]
  if(-not $r){ return $null }
  $best=$null; $bd=99999
  foreach($n in (Nodes $x)){
    if($n.cls -ne 'android.widget.TextView'){ continue }
    $l=$n.text; if(-not $l -or $l -eq ''){ continue }
    if($l -eq $name){ continue }
    $b=Bounds $n; if(-not $b){ continue }
    $d = $b.y1 - $r.node.bounds
    if($b.x1 -ge 180 -and $b.x1 -le 260 -and $d -gt 0 -and $d -lt 80 -and $d -lt $bd){ $bd=$d; $best=$l }
  }
  return $best
}

# ---- scroll a row into view; returns $true if visible ----
function ScrollToRow([string]$addr,[int]$max=6){
  for($k=0;$k -lt $max;$k++){
    $x = Dump "c9_scroll_$k"
    $hit = FindRow $x $addr
    if($hit.Count -gt 0){
      Log "  SCROLL: '$addr' VISIBLE after $k scroll(s) at $(BStr $hit[0].node)"
      return $x
    }
    # scroll down a bit inside the list
    & $ADB -s $DEV shell input swipe 540 1800 540 1200 350 2>&1 | Out-Null
    Start-Sleep -Milliseconds 900
  }
  $x = Dump "c9_scroll_fail"
  Log "  SCROLL: '$addr' NOT VISIBLE after $max swipes"
  return $null
}

# ---- kebab (row overflow menu). Real content-desc in this build = "Server actions" ----
function FindKebabNear($x,[string]$name){
  $r = (FindRow $x $name)[0]
  if(-not $r){ Log "  KEBAB: row '$name' NOT VISIBLE"; return $null }
  $cands = @((Nodes $x) | Where-Object { (Lbl $_) -match '^Server actions$' })
  $best=$null; $bd=999999
  foreach($c in $cands){
    $b=Bounds $c
    if(-not $b){ continue }
    $d=[math]::Abs($b.cy-$r.cy)
    if($d -lt $bd){ $bd=$d; $best=$c }
  }
  if($best){
    $b=Bounds $best
    Log ("  KEBAB near '{0}': 'Server actions' {0} cy-delta={1} clickable={2} enabled={3}" -f (BStr $best),[int]$bd,$best.clk,$best.en)
  } else { Log "  KEBAB near '$name': NONE in dump (enabled-state evidence below)" }
  return $best
}

# ---- full text of a dump, for status assertion ----
function AllText($x){
  $s=@()
  foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -ne ''){ $s+=$l } }
  return $s
}
function HasTxt($x,[string]$pat){ return ((AllText $x) -match $pat).Count -gt 0 }

# ---- R9 additions ----
function TapLabelScroll([string]$t,[int]$max=5){
  for($k=0;$k -lt $max;$k++){
    $x = Dump ("c9_ts_" + ($t -replace '\W','_') + "_$k")
    $h = @(FindTxt $x $t)[0]
    if($h){ $b=Bounds $h; TapXY $b.cx $b.cy 1400 "tap-$t"; return $true }
    & $ADB -s $DEV shell input swipe 540 1200 540 1900 350 2>&1 | Out-Null
    Start-Sleep -Milliseconds 700
  }
  Log "  TAPSCROLL-MISS '$t' after $max up-scrolls"
  return $false
}

function ScrollToName([string]$name,[int]$max=6){
  for($k=0;$k -lt $max;$k++){
    $x = Dump ("c9_sn_" + $k)
    $hit = @(FindRow $x $name)[0]
    if($hit){ Log "  SCROLL: '$name' VISIBLE at $($hit.b)"; return $x }
    & $ADB -s $DEV shell input swipe 540 1800 540 1250 350 2>&1 | Out-Null
    Start-Sleep -Milliseconds 800
  }
  Log "  SCROLL: '$name' NOT VISIBLE after $max swipes"
  return $null
}

# every EditText in order
function EditFields($x){ return @((Nodes $x) | Where-Object { $_.cls -eq 'android.widget.EditText' }) }

function FillEdit($x,[string]$hint,[string]$val){
  # find EditText whose text or hint matches $hint
  $cands = @()
  foreach($n in (Nodes $x)){
    if($n.cls -ne 'android.widget.EditText'){ continue }
    $l = Lbl $n
    $raw = ''
    if($n.rid -and $n.rid -match $hint){ $cands+=$n }
    elseif($l -and $l -match $hint){ $cands+=$n }
  }
  $h = @($cands)[0]
  if(-not $h){ Log "  FILL-MISS field '$hint'"; return $false }
  $b = Bounds $h
  & $ADB -s $DEV shell input tap $b.cx $b.cy 2>&1 | Out-Null
  Start-Sleep -Milliseconds 500
  & $ADB -s $DEV shell input keyevent KEYCODE_MOVE_END 2>&1 | Out-Null
  for($i=0;$i -lt 70;$i++){ & $ADB -s $DEV shell input keyevent 67 2>&1 | Out-Null }
  if($val -ne ''){ TypeTxt $val }
  Log ("  FILL '{0}' = '{1}' at {2}" -f $hint,$val,(BStr $h))
  Start-Sleep -Milliseconds 400
  return $true
}

# dump form structure compactly
function FormInfo($x){
  foreach($n in (Nodes $x)){
    $l = Lbl $n
    if($n.cls -match 'EditText|CheckBox|Switch|Button' -or ($l -and $l -ne '')){
      Log ("  '{0}' cls={1} b={2} clk={3} en={4} rid={5}" -f $l,$n.cls,$n.b,$n.clk,$n.en,$n.rid)
    }
  }
}

# state snapshot for a row: status text + kebab presence/enabled
function RowState($x,[string]$name){
  $r = @(FindRow $x $name)[0]
  if(-not $r){ return [pscustomobject]@{name=$name; visible=$false; status='(row not visible)'; kebab=0; kebabEn=''; kebabB=''} }
  $st = RowStatus $x $name
  $kb = FindKebabNear $x $name
  return [pscustomobject]@{
    name=$name; visible=$true
    status= $(if($st){$st}else{'(no status line)'})
    kebab=$(if($kb){1}else{0})
    kebabEn=$(if($kb){$kb.en}else{''})
    kebabB=$(if($kb){$kb.b}else{''})
  }
}

# find the EditText anchored under a label (label y1 .. y1+140), x1>=130 (sheet only)
function FieldUnder($x,[string]$labelText){
  $lab = @(FindTxt $x $labelText)[0]
  if(-not $lab){ Log "  LABEL-MISS '$labelText'"; return $null }
  $lb = Bounds $lab
  $best=$null; $bd=99999
  foreach($n in (Nodes $x)){
    if($n.cls -ne 'android.widget.EditText'){ continue }
    $b = Bounds $n
    if(-not $b){ continue }
    if($b.x1 -lt 130){ continue }
    $dy = $b.y1 - $lb.y1
    if($dy -ge 0 -and $dy -lt 150 -and $dy -lt $bd){ $bd=$dy; $best=$n }
  }
  if($best){ Log ("  FIELD '{0}' -> {1} (dy={2})" -f $labelText,$best.b,[int]$bd) }
  else { Log "  FIELD-MISS under '$labelText'" }
  return $best
}
function SetField($x,[string]$labelText,[string]$val){
  $n = FieldUnder $x $labelText
  if(-not $n){ return $false }
  $b = Bounds $n
  & $ADB -s $DEV shell input tap $b.cx $b.cy 2>&1 | Out-Null
  Start-Sleep -Milliseconds 500
  & $ADB -s $DEV shell input keyevent KEYCODE_MOVE_END 2>&1 | Out-Null
  for($i=0;$i -lt 90;$i++){ & $ADB -s $DEV shell input keyevent 67 2>&1 | Out-Null }
  if($val -ne ''){ TypeTxt $val }
  Log ("  SET '{0}' = '{1}'" -f $labelText,$val)
  return $true
}
