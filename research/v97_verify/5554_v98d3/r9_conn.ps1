# r9_conn.ps1 - capture Connecting-state kebab evidence, submit password, poll to Connected.
$ErrorActionPreference='Continue'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3"
Set-Location $d
. "$d\c9lib.ps1"
$TARGET='127.0.0.1:2222'

Log "=== R9 G1: CONNECTING-state evidence (auth card up) ==="
for($k=0;$k -lt 4;$k++){
  $x = Dump "c9_G1_conn$k"
  $row = @(FindRow $x $TARGET)[0]
  $kb  = FindKebabNear $x $TARGET
  $statuses = @()
  foreach($n in (Nodes $x)){
    $l=Lbl $n; $b=Bounds $n
    if($l -and $l -match 'Connecting|Connected|Authentication required|Disconnected|Connection failed' -and $b -and $b.y1 -gt 1500){
      $statuses+=$l }
  }
  Log ("  conn#{0} rowVisible={1} kebab={2} statusNearRow=[{3}]" -f $k,([bool]$row),([bool]$kb),($statuses -join ' | '))
  Start-Sleep -Milliseconds 500
}

Log "=== R9 G2: submit password via 'Connecting over SSH' ==="
$x = Dump "c9_G2a"
$btn = @((Nodes $x) | Where-Object { (Lbl $_) -match 'Connecting over SSH' -and $_.b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]' -and [int]$matches[3] -gt 1000 })[0]
if(-not $btn){ $btn = @(FindLike $x 'Connecting over SSH')[0] }
if($btn){ $b=Bounds $btn; Log "  submit btn b=$($btn.b) txt='$(Lbl $btn)'"; TapXY $b.cx $b.cy 2000 "Connecting over SSH" }
else { Log "  submit btn NOT FOUND; labels:"; foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -match 'Cancel|Connecting|Trust|Connect|OK'){ Log "    '$l' $($n.b)" } } }

Log "=== R9 G3: poll for state (handle trust card) ==="
$seenStates=@{}
for($i=0;$i -lt 40;$i++){
  $x = Dump "c9_G3_$i"
  # trust card?
  $trust = @((Nodes $x) | Where-Object { (Lbl $_) -match 'Trust and connect|^Trust$|Fingerprint|Continue anyway' })[0]
  if($trust){
    Log ("  [i={0}] TRUST CARD up: '{1}' {2}" -f $i,(Lbl $trust),$trust.b)
    $b=Bounds $trust; TapXY $b.cx $b.cy 1800 "Trust and connect"
    continue
  }
  $row = @(FindRow $x $TARGET)[0]
  $st  = RowStatus $x $TARGET
  $kb  = FindKebabNear $x $TARGET
  $line = $(if($row){ $st } else { '(row not visible)' })
  $key = "$line"
  if(-not $seenStates.ContainsKey($key)){ $seenStates[$key]=1; Log ("  [i={0}] STATE='{1}' rowVisible={2} kebab={3} kebabEn='{4}'" -f $i,$line,([bool]$row),([bool]$kb),$(if($kb){$kb.en}else{''})) }
  if($line -match 'Connected'){ Log "  *** REACHED Connected at i=$i ***"; break }
  if($line -match 'Authentication required'){ Log "  *** REACHED Authentication required at i=$i ***" }
  Start-Sleep -Milliseconds 1200
}
Log "=== G3 distinct states seen ==="
foreach($k in $seenStates.Keys){ Log "  STATE_SEEN: '$k'" }
$x = Dump "c9_G3_final"
Log "--- final labels ---"
foreach($n in (Nodes $x)){ $l=Lbl $n; if($l -and $l -ne ''){ Log ("  '{0}' b={1} en={2}" -f $l,$n.b,$n.en) } }
Log "focus=$(Focus)"
