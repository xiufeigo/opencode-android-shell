# r9_form2.ps1 - minimal reliable form fill: HOST only (+Password), name left to default.
$ErrorActionPreference='Continue'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
Set-Location $d
. "$d\c9lib.ps1"

Log "=== R9 E1: HOST + Password only ==="
ImeOff | Out-Null

# --- 1. ensure form is open, scroll sheet to top
for($k=0;$k -lt 4;$k++){ & $ADB -s $DEV shell input swipe 540 1000 540 1700 400 2>&1 | Out-Null; Start-Sleep -Milliseconds 500; ImeOff | Out-Null }
$x = Dump "c9_E1a"
$hostLab = @(FindTxt $x 'Host or SSH command')[0]
if(-not $hostLab){
  # form closed -> reopen
  for($k=0;$k -lt 5;$k++){ & $ADB -s $DEV shell input swipe 540 1200 540 1900 350 2>&1 | Out-Null; Start-Sleep -Milliseconds 400 }
  ImeOff | Out-Null
  $x = Dump "c9_E1a2"
  $a=@(FindTxt $x 'Add SSH server')[0]
  if($a){ $b=Bounds $a; TapXY $b.cx $b.cy 1600 "reopen Add" }
  ImeOff | Out-Null
  $x = Dump "c9_E1a3"
  $hostLab = @(FindTxt $x 'Host or SSH command')[0]
}
Log "  form open = $([bool]$hostLab)"

function TypeIntoLabel([string]$labTxt,[string]$val,[string]$tag){
  $xx = Dump "c9_${tag}_p"
  $lab = @(FindTxt $xx $labTxt)[0]
  if(-not $lab){ Log "  LBL-MISS '$labTxt'"; return $false }
  $lb = Bounds $lab
  $tgt=$null; $bd=99999
  foreach($n in (Nodes $xx)){
    if($n.cls -ne 'android.widget.EditText'){ continue }
    $b=Bounds $n; if(-not $b){ continue }
    if($b.x1 -lt 130){ continue }
    $dy=$b.y1-$lb.y1
    if($dy -ge 0 -and $dy -lt 150 -and $dy -lt $bd){ $bd=$dy; $tgt=$n }
  }
  if(-not $tgt){ Log "  EDT-MISS under '$labTxt'"; return $false }
  $b=Bounds $tgt
  & $ADB -s $DEV shell input tap $b.cx $b.cy 2>&1 | Out-Null
  Start-Sleep -Milliseconds 500
  & $ADB -s $DEV shell input keyevent KEYCODE_MOVE_END 2>&1 | Out-Null
  for($i=0;$i -lt 110;$i++){ & $ADB -s $DEV shell input keyevent 67 2>&1 | Out-Null }
  if($val -ne ''){ TypeTxt $val }
  ImeOff | Out-Null
  $xv = Dump "c9_${tag}_v"
  $lb2 = @(FindTxt $xv $labTxt)[0]
  $got=''
  if($lb2){ $lb=Bounds $lb2; $bd2=99999
    foreach($n in (Nodes $xv)){
      if($n.cls -ne 'android.widget.EditText'){ continue }
      $b=Bounds $n; if(-not $b){ continue }
      if($b.x1 -lt 130){ continue }
      $dy=$b.y1-$lb.y1
      if($dy -ge 0 -and $dy -lt 150 -and $dy -lt $bd2){ $bd2=$dy; $got=$n.text } } }
  Log ("  TYPE '{0}' -> got='{1}' {2}" -f $labTxt,$got,$(if($got -eq $val){'OK'}else{'MISMATCH'}))
  return ($got -eq $val)
}

TypeIntoLabel 'Host or SSH command' 'sshtest@127.0.0.1:2222' 'host' | Out-Null

# --- 2. scroll sheet down and set Password
for($k=0;$k -lt 3;$k++){ & $ADB -s $DEV shell input swipe 540 1600 540 950 400 2>&1 | Out-Null; Start-Sleep -Milliseconds 550; ImeOff | Out-Null }
$x = Dump "c9_E1_pwtop"
foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -match 'Password|Host|Server name|Add server|Cancel'){ Log ("  LBL '{0}' b={1}" -f $l,$n.b) } }
TypeIntoLabel 'Password' 'opencode' 'pw' | Out-Null

# --- 3. submit
$x = Dump "c9_E1_sub"
$ad = @(FindTxt $x 'Add server')[0]
if($ad){ $b=Bounds $ad; TapXY $b.cx $b.cy 2500 "Add server" } else { Log "  NO Add server" }
$x = Dump "c9_E1_done"
Log "--- after submit ---"
foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -ne ''){ Log ("  '{0}' b={1}" -f $l,$n.b) } }
Log "--- row census (scroll) ---"
$seen=@{}
for($k=0;$k -lt 6;$k++){
  $y = Dump "c9_E1_c_$k"
  foreach($r in (RowLines $y)){ if(-not $seen.ContainsKey($r.name)){ $seen[$r.name]=1; Log ("  ROW '{0}' {1}" -f $r.name,$r.b) } }
  if((RowCount $y) -eq 0){ break }
  & $ADB -s $DEV shell input swipe 540 1800 540 1300 350 2>&1 | Out-Null
  Start-Sleep -Milliseconds 650
}
Log "=== DISTINCT = $($seen.Count) ==="
foreach($k in ($seen.Keys|Sort-Object)){ Log "  ENTRY: '$k'" }
