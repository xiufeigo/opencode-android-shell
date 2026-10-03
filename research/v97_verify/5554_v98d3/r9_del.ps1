# r9_del.ps1 - scroll target row to mid-screen, test own kebab, delete in current state, verify.
$ErrorActionPreference='Continue'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
Set-Location $d
. "$d\c9lib.ps1"
$TARGET='127.0.0.1:2222'

Log "=== R9 I1: dismiss card, scroll target to mid-screen ==="
ImeOff | Out-Null
$x = Dump "c9_I1a"
$cc = @(FindTxt $x 'Cancel')[0]
if($cc){ $b=Bounds $cc; Log "  dismiss card via Cancel $($cc.b)"; TapXY $b.cx $b.cy 1500 "Cancel card" }
Start-Sleep -Milliseconds 600

# scroll DOWN so the target (currently last) moves up into mid-screen
for($k=0;$k -lt 4;$k++){ & $ADB -s $DEV shell input swipe 540 1800 540 1250 350 2>&1 | Out-Null; Start-Sleep -Milliseconds 700 }
ImeOff | Out-Null

Log "=== R9 I2: kebab presence at mid-screen ==="
$ownKb=$null; $row=$null
for($k=0;$k -lt 5;$k++){
  $x = Dump "c9_I2_$k"
  $row = @(FindRow $x $TARGET)[0]
  if($row){ Log "  row at $($row.b) cy=$($row.cy)" } else { Log "  row NOT visible at pass $k" }
  if($row){
    $cands = @((Nodes $x) | Where-Object { (Lbl $_) -eq 'Server actions' })
    Log "  'Server actions' nodes: $(($cands | ForEach-Object { $_.b }) -join ' , ')"
    foreach($n in $cands){ $b=Bounds $n; $dd=[math]::Abs($b.cy-$row.cy)
      Log ("     kebab {0} cy={1} deltaFromRow={2} en={3} clk={4}" -f $n.b,$b.cy,[int]$dd,$n.en,$n.clk) }
    foreach($n in $cands){ $b=Bounds $n; if([math]::Abs($b.cy-$row.cy) -le 70){ $ownKb=$n } }
  }
  if($ownKb){ break }
  if(-not $row){ & $ADB -s $DEV shell input swipe 540 1800 540 1250 350 2>&1 | Out-Null; Start-Sleep -Milliseconds 700 }
}
Log ("  OWN KEBAB FOUND = {0}" -f [bool]$ownKb)

if($ownKb){
  Log "=== I3: open row menu ==="
  $b=Bounds $ownKb; TapXY $b.cx $b.cy 1500 "Server actions"
  $x = Dump "c9_I3_menu"
  Log "  --- menu items ---"
  foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -ne '' -and $l -notmatch '^(Home|New session|Command palette|Projects|Add project|More options|Close|Settings|Help)$'){ Log ("  '{0}' b={1}" -f $l,$n.b) } }
  $del = @((Nodes $x) | Where-Object { (Lbl $_) -match '^Delete$|Delete ' })[0]
  if($del){
    $b=Bounds $del; Log "  tap Delete $($del.b)"; TapXY $b.cx $b.cy 1500 "Delete"
    $x = Dump "c9_I4_confirm"
    Log "  --- confirm dialog ---"
    foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -ne '' -and $l -notmatch '^(Home|New session|Command palette|Projects|Add project|More options|Settings|Help)$'){ Log ("  '{0}' b={1}" -f $l,$n.b) } }
    # confirm button
    $cf = @((Nodes $x) | Where-Object { (Lbl $_) -match '^(Remove|Delete|Cancel|OK|Confirm)$' })[0]
    if($cf){ $b=Bounds $cf; Log "  tap confirm '$($cf.b)' -> $(Lbl $cf)"; TapXY $b.cx $b.cy 2000 "confirm" }
    else { Log "  NO confirm button found" }
    $x = Dump "c9_I5_after"
    Log "=== I5: census AFTER delete ==="
    $seen=@{}
    for($k=0;$k -lt 6;$k++){
      $y = Dump "c9_I5_c_$k"
      foreach($r in (RowLines $y)){ if(-not $seen.ContainsKey($r.name)){ $seen[$r.name]=1; Log ("  ROW '{0}' {1}" -f $r.name,$r.b) } }
      if((RowCount $y) -eq 0){ break }
      & $ADB -s $DEV shell input swipe 540 1800 540 1300 350 2>&1 | Out-Null
      Start-Sleep -Milliseconds 650
    }
    Log "  DISTINCT = $($seen.Count)"
    foreach($k in ($seen.Keys|Sort-Object)){ Log "  ENTRY: '$k'" }
    Log ("  TARGET STILL PRESENT = {0}" -f ($seen.ContainsKey($TARGET)))
    Log ("  10.0.2.2:4095 INTACT = {0}" -f ($seen.ContainsKey('10.0.2.2:4095')))
  } else { Log "  DELETE item not found in menu" }
} else { Log "=== NO OWN KEBAB -> cannot open row menu in this state ===" }
Log "focus=$(Focus)"
