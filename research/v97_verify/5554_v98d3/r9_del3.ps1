# r9_del3.ps1 - delete target with CORRECT kebab mapping (kebab cy = name cy + ~203).
$ErrorActionPreference='Continue'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
Set-Location $d
. "$d\c9lib.ps1"
$TARGET='127.0.0.1:2222'

# The row's own kebab is the one whose cy is ~203px BELOW the row name cy.
# Verified: c3 name cy=975 -> kebab cy=1178 (delta 203); 10.0.2.2:4095 name cy~1547 -> kebab cy=1750 (delta 203).
function KebabFor($x,$name){
  $r = @(FindRow $x $name)[0]
  if(-not $r){ Log "  KEBABFOR: row '$name' not visible"; return $null }
  $cands = @((Nodes $x) | Where-Object { (Lbl $_) -eq 'Server actions' })
  $out=@()
  foreach($n in $cands){ $b=Bounds $n; $out+=("{0}@{1}" -f $n.b,$b.cy) }
  Log ("  kebab nodes: {0}" -f ($out -join ' , '))
  foreach($n in $cands){
    $b=Bounds $n
    $d=$b.cy - $r.cy
    if($d -ge 180 -and $d -le 230){
      Log ("  KEBABFOR '{0}': {1} cy={2} (name cy={3}, delta=+{4}) -> OWN" -f $name,$n.b,$b.cy,$r.cy,$d)
      return $n
    }
  }
  Log ("  KEBABFOR '{0}': no kebab at +180..230 from name cy={1}" -f $name,$r.cy)
  return $null
}

Log "=== R9 K1: census BEFORE ==="
$before=@()
for($k=0;$k -lt 6;$k++){
  $y = Dump "c9_K1_c_$k"
  foreach($r in (RowLines $y)){ if($before -notcontains $r.name){ $before+=$r.name; Log ("  ROW '{0}' {1}" -f $r.name,$r.b) } }
  if((RowCount $y) -eq 0){ break }
  & $ADB -s $DEV shell input swipe 540 1800 540 1300 350 2>&1 | Out-Null
  Start-Sleep -Milliseconds 600
}
Log "  BEFORE($($before.Count)) = $($before -join ' | ')"

Log "=== R9 K2: find target + its own kebab ==="
ImeOff | Out-Null
$kb=$null
for($k=0;$k -lt 6;$k++){
  $x = Dump "c9_K2_$k"
  $r = @(FindRow $x $TARGET)[0]
  if($r){
    Log "  target at $($r.b) cy=$($r.cy)"
    $c = KebabFor $x $TARGET
    if($c){ $kb=$c; break }
  } else { Log "  target not visible pass $k" }
  & $ADB -s $DEV shell input swipe 540 1700 540 1350 350 2>&1 | Out-Null
  Start-Sleep -Milliseconds 700
}

if($kb){
  $b=Bounds $kb
  Log "  TAP kebab $($kb.b) -> $($b.cx),$($b.cy)"
  TapXY $b.cx $b.cy 1600 "Server actions"
  $x = Dump "c9_K3_menu"
  $del = @((Nodes $x) | Where-Object { (Lbl $_) -match '^Delete' })[0]
  if($del){
    $b=Bounds $del; Log "  DELETE item $($del.b)"; TapXY $b.cx $b.cy 1600 "Delete"
    $x = Dump "c9_K4_confirm"
    $dlgs = @((Nodes $x) | Where-Object { (Lbl $_) -match 'removes|Delete SSH server' })
    foreach($n in $dlgs){ Log ("  DIALOG: '{0}'" -f (Lbl $n)) }
    $cf = @((Nodes $x) | Where-Object { (Lbl $_) -match '^Delete$' -and (Bounds $_).y1 -gt 1300 })[0]
    if(-not $cf){ $cf = @((Nodes $x) | Where-Object { (Lbl $_) -match '^Delete$' })[-1] }
    if($cf){ $b=Bounds $cf; Log "  CONFIRM $($cf.b)"; TapXY $b.cx $b.cy 2000 "confirm" }
    else { Log "  no confirm button" }
  } else { Log "  DELETE item not found" }
} else { Log "  NO OWN KEBAB" }

Log "=== R9 K3: census AFTER ==="
$after=@()
for($k=0;$k -lt 6;$k++){
  $y = Dump "c9_K3_c_$k"
  foreach($r in (RowLines $y)){ if($after -notcontains $r.name){ $after+=$r.name; Log ("  ROW '{0}' {1}" -f $r.name,$r.b) } }
  if((RowCount $y) -eq 0){ break }
  & $ADB -s $DEV shell input swipe 540 1800 540 1300 350 2>&1 | Out-Null
  Start-Sleep -Milliseconds 600
}
Log "  AFTER($($after.Count)) = $($after -join ' | ')"
Log ("  TARGET REMOVED = {0}" -f (-not ($after -contains $TARGET)))
Log ("  10.0.2.2:4095 INTACT = {0}" -f ($after -contains '10.0.2.2:4095'))
Log ("  E2ECHK5554d3e-c2 INTACT = {0}" -f ($after -contains 'E2ECHK5554d3e-c2'))
Log "focus=$(Focus)"
