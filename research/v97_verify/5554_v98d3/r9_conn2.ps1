# r9_conn2.ps1 - retry connect from the auth card, poll states, capture kebab evidence.
$ErrorActionPreference='Continue'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
Set-Location $d
. "$d\c9lib.ps1"
$TARGET='127.0.0.1:2222'

Log "=== R9 H1: tap Connect on auth card, poll ==="
$x = Dump "c9_H1a"
$cb = @(FindTxt $x 'Connect')[0]
if($cb){ $b=Bounds $cb; Log "  Connect btn $($cb.b) en=$($cb.en)"; TapXY $b.cx $b.cy 2000 "Connect" }
else { Log "  Connect btn not found" }

$seen=@{}
for($i=0;$i -lt 25;$i++){
  $x = Dump "c9_H1_$i"
  $trust = @((Nodes $x) | Where-Object { (Lbl $_) -match 'Trust and connect|^Trust$|Fingerprint' })[0]
  if($trust){ Log ("  [i={0}] TRUST CARD '{1}'" -f $i,(Lbl $trust)); $b=Bounds $trust; TapXY $b.cx $b.cy 1800 "Trust"; continue }
  $row = @(FindRow $x $TARGET)[0]
  $st = RowStatus $x $TARGET
  # own-row kebab: must be within 60px of the row cy
  $ownKb = $null
  if($row){
    foreach($n in (Nodes $x)){
      if((Lbl $n) -ne 'Server actions'){ continue }
      $b=Bounds $n; if(-not $b){ continue }
      if([math]::Abs($b.cy - $row.cy) -le 60){ $ownKb=$n; break }
    }
  }
  # collect any state-ish text on screen
  $cand = @()
  foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -match '^(Connecting over SSH.*|Connected.*|Authentication required|Disconnected.*|Connection failed|Could not establish.*)$'){ $cand+=$l } }
  $key = (($cand | Select-Object -Unique) -join ' ; ')
  if(-not $seen.ContainsKey($key)){ $seen[$key]=1; Log ("  [i={0}] rowVisible={1} ownKebab={2} states=[{3}]" -f $i,([bool]$row),([bool]$ownKb),$key) }
  if($key -match 'Connected'){ Log "  *** Connected at i=$i ***"; break }
  Start-Sleep -Milliseconds 1100
}
Log "=== H1 distinct ==="
foreach($k in $seen.Keys){ Log "  SET: '$k'" }
$x = Dump "c9_H1_final"
foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -ne ''){ Log ("  '{0}' b={1}" -f $l,$n.b) } }
