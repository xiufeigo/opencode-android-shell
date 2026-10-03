# r9_form3.ps1 - fill the REQUIRED Password field, then submit.
$ErrorActionPreference='Continue'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
Set-Location $d
. "$d\c9lib.ps1"

Log "=== R9 F1: fill Password (required) + submit ==="
ImeOff | Out-Null
$x = Dump "c9_F1a"
$pl = @(FindTxt $x 'Password')[0]
Log "  Password label present = $([bool]$pl)"
if($pl){
  $lb = Bounds $pl
  $tgt=$null; $bd=99999
  foreach($n in (Nodes $x)){
    if($n.cls -ne 'android.widget.EditText'){ continue }
    $b=Bounds $n; if(-not $b){ continue }
    if($b.x1 -lt 130){ continue }
    $dy=$b.y1-$lb.y1
    if($dy -ge 0 -and $dy -lt 200 -and $dy -lt $bd){ $bd=$dy; $tgt=$n }
  }
  if($tgt){
    $b=Bounds $tgt
    Log "  password EditText = $($tgt.b) (dy=$bd)"
    & $ADB -s $DEV shell input tap $b.cx $b.cy 2>&1 | Out-Null
    Start-Sleep -Milliseconds 600
    & $ADB -s $DEV shell input keyevent KEYCODE_MOVE_END 2>&1 | Out-Null
    for($i=0;$i -lt 110;$i++){ & $ADB -s $DEV shell input keyevent 67 2>&1 | Out-Null }
    TypeTxt 'opencode'
    ImeOff | Out-Null
    $xv = Dump "c9_F1b"
    $pl2 = @(FindTxt $xv 'Password')[0]
    if($pl2){ $lb=Bounds $pl2; $bd2=99999; $got=''
      foreach($n in (Nodes $xv)){
        if($n.cls -ne 'android.widget.EditText'){ continue }
        $b=Bounds $n; if(-not $b){ continue }
        if($b.x1 -lt 130){ continue }
        $dy=$b.y1-$lb.y1
        if($dy -ge 0 -and $dy -lt 200 -and $dy -lt $bd2){ $bd2=$dy; $got=$n.text } }
      Log "  password now = '$got'" }
    foreach($n in (Nodes $xv)){ if($n.cls -eq 'android.widget.EditText'){ Log ("  EDIT {0} val='{1}'" -f $n.b,$n.text) } }
  } else { Log "  no EditText under Password" }
}
# submit
$x = Dump "c9_F1c"
$ad = @(FindTxt $x 'Add server')[0]
if($ad){ Log "  Add server node en=$($ad.en) b=$($ad.b)"; $b=Bounds $ad; TapXY $b.cx $b.cy 2500 "Add server" }
Start-Sleep -Milliseconds 800
$x = Dump "c9_F1d"
$still = @((Nodes $x) | Where-Object { (Lbl $_) -eq 'Cancel' }).Count
Log "  Cancel still present (form open?) = $still"
Log "--- labels after submit ---"
foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -ne ''){ Log ("  '{0}' b={1}" -f $l,$n.b) } }
Log "--- census ---"
$seen=@{}
for($k=0;$k -lt 6;$k++){
  $y = Dump "c9_F1_c_$k"
  foreach($r in (RowLines $y)){ if(-not $seen.ContainsKey($r.name)){ $seen[$r.name]=1; Log ("  ROW '{0}' {1} status='{2}'" -f $r.name,$r.b,(RowStatus $y $r.name)) } }
  if((RowCount $y) -eq 0){ break }
  & $ADB -s $DEV shell input swipe 540 1800 540 1300 350 2>&1 | Out-Null
  Start-Sleep -Milliseconds 650
}
Log "=== DISTINCT = $($seen.Count) ==="
foreach($k in ($seen.Keys|Sort-Object)){ Log "  ENTRY: '$k'" }
