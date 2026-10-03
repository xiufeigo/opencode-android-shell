# r9_del2.ps1 - delete target row in its current (non-Disconnected) state; verify.
$ErrorActionPreference='Continue'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
Set-Location $d
. "$d\c9lib.ps1"
$TARGET='127.0.0.1:2222'

# kebab sits ~203px below the name cy (verified against known-good row c3)
function OwnKebab($x,$name){
  $r = @(FindRow $x $name)[0]
  if(-not $r){ return $null }
  $best=$null; $bd=99999
  foreach($n in (Nodes $x)){
    if((Lbl $n) -ne 'Server actions'){ continue }
    $b=Bounds $n; if(-not $b){ continue }
    $d=[math]::Abs($b.cy-$r.cy)
    if($d -lt $bd){ $bd=$d; $best=$n; $bestD=$d }
  }
  if($best){ Log ("  OWN-KEBAB for '{0}': {1} cy-delta={2} en={3}" -f $name,$best.b,[int]$bestD,$best.en) }
  else { Log "  OWN-KEBAB for '$name': none" }
  return $best
}

Log "=== R9 J1: census BEFORE delete ==="
$x = Dump "c9_J1a"
$before=@()
for($k=0;$k -lt 6;$k++){
  $y = Dump "c9_J1_c_$k"
  foreach($r in (RowLines $y)){ if($before -notcontains $r.name){ $before+=$r.name; Log ("  ROW '{0}' {1}" -f $r.name,$r.b) } }
  if((RowCount $y) -eq 0){ break }
  & $ADB -s $DEV shell input swipe 540 1800 540 1300 350 2>&1 | Out-Null
  Start-Sleep -Milliseconds 650
}
Log "  BEFORE($($before.Count)) = $($before -join ' | ')"

Log "=== R9 J2: locate target mid-screen + open its menu ==="
ImeOff | Out-Null
$kb=$null
for($k=0;$k -lt 5;$k++){
  $x = Dump "c9_J2_$k"
  $r = @(FindRow $x $TARGET)[0]
  if($r -and $r.cy -gt 1000 -and $r.cy -lt 1600){ Log "  target mid-screen at $($r.b)"; $kb = OwnKebab $x $TARGET; break }
  if(-not $r){ Log "  target not visible pass $k" }
  & $ADB -s $DEV shell input swipe 540 1700 540 1350 350 2>&1 | Out-Null
  Start-Sleep -Milliseconds 700
}
if(-not $kb){ $x = Dump "c9_J2f"; $kb = OwnKebab $x $TARGET }

if($kb){
  $b=Bounds $kb
  Log "  tapping kebab $($kb.b) -> $($b.cx),$($b.cy)"
  TapXY $b.cx $b.cy 1600 "Server actions"
  $x = Dump "c9_J3_menu"
  Log "  --- menu items ---"
  foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -ne ''){ Log ("  '{0}' b={1}" -f $l,$n.b) } }
  $del = @((Nodes $x) | Where-Object { (Lbl $_) -match '^Delete' })[0]
  if($del){
    $b=Bounds $del; Log "  DELETE item $($del.b)"; TapXY $b.cx $b.cy 1600 "Delete"
    $x = Dump "c9_J4_confirm"
    Log "  --- confirm dialog ---"
    foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -ne ''){ Log ("  '{0}' b={1}" -f $l,$n.b) } }
    $cf = @((Nodes $x) | Where-Object { (Lbl $_) -match '^(Remove|Delete)$' })[0]
    if($cf){ $b=Bounds $cf; Log "  CONFIRM '$($cf.b)'"; TapXY $b.cx $b.cy 2000 "confirm" }
    else { Log "  no Remove/Delete confirm button" }
  } else { Log "  DELETE not in menu" }
} else { Log "  NO KEBAB -> cannot delete from row" }

Log "=== R9 J5: census AFTER delete ==="
$after=@()
for($k=0;$k -lt 6;$k++){
  $y = Dump "c9_J5_c_$k"
  foreach($r in (RowLines $y)){ if($after -notcontains $r.name){ $after+=$r.name; Log ("  ROW '{0}' {1}" -f $r.name,$r.b) } }
  if((RowCount $y) -eq 0){ break }
  & $ADB -s $DEV shell input swipe 540 1800 540 1300 350 2>&1 | Out-Null
  Start-Sleep -Milliseconds 650
}
Log "  AFTER($($after.Count)) = $($after -join ' | ')"
Log ("  TARGET REMOVED = {0}" -f (-not ($after -contains $TARGET)))
Log ("  10.0.2.2:4095 INTACT = {0}" -f ($after -contains '10.0.2.2:4095'))
Log ("  E2ECHK5554d3e-c2 INTACT = {0}" -f ($after -contains 'E2ECHK5554d3e-c2'))
Log ("  E2ECHK5554d3e-c3 INTACT = {0}" -f ($after -contains 'E2ECHK5554d3e-c3'))
Log "focus=$(Focus)"
