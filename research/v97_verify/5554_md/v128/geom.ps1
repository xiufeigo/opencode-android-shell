param([string]$Tag)
$ErrorActionPreference='Stop'
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\v128\helpers.ps1

$ime = (A shell dumpsys input_method | Select-String 'mInputShown' | Select-Object -First 1).ToString().Trim()
"### mInputShown: $ime"
if ($ime -notmatch 'true') { "!!! ABORT: mInputShown != true -- refusing to read heights"; exit 2 }
"### focus: $(Focus)"
"### wm: $((A shell wm size | Select-String Override).ToString().Trim())"
"### density: $((A shell wm density | Select-String Override).ToString().Trim())"
"### appPid: $(AppPid)"

$f = Join-Path $OUT "${Tag}_full.xml"
if (-not (Test-Path $f)) { $f = Dump "${Tag}_full.xml" }
$n = Nodes $f
"### nodes: $($n.Count)"

"`n=== PANE CANDIDATES (full-width containers, y1>=100) ==="
$n | Where-Object { $_.x1 -le 4 -and $_.wd -ge 900 -and $_.y1 -ge 100 } | ForEach-Object {
  "y1=$($_.y1) y2=$($_.y2) h=$($_.ht) w=$($_.wd) class=$($_.class)"
}

"`n=== COMPOSER SUB-NODES (y1 >= paneTop, excluding Open terminal) ==="
$openTerm = $n | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
if ($openTerm) { "excluded: Open terminal y1=$($openTerm.y1) y2=$($openTerm.y2)" }
$comp = $n | Where-Object { $_.y1 -ge 150 -and -not ($_.desc -eq 'Open terminal') -and
  (($_.class -match 'EditText|Button|HorizontalScrollView|TextView|View') -and ($_.wd -le 1330)) }
$comp | Sort-Object y1 | ForEach-Object {
  "cls=$($_.class) x1=$($_.x1) y1=$($_.y1) x2=$($_.x2) y2=$($_.y2) w=$($_.wd) h=$($_.ht) clk=$($_.clickable) desc=[$($_.desc)] text=[$($_.text)]"
}

"`n=== UNION (composer children minus Open terminal) ==="
# union = nodes strictly inside the pane box, excluding Open terminal and excluding the pane box itself
$pane = $n | Where-Object { $_.x1 -le 4 -and $_.wd -ge 900 -and $_.y1 -ge 100 } | Sort-Object ht | Select-Object -Last 1
if ($pane) {
  "paneBox = [$($pane.x1),$($pane.y1)][$($pane.x2),$($pane.y2)]  h=$($pane.ht)  => paneBottom(i)=$($pane.y2)"
  # union = COMPOSER children only. The composer starts at the editor top;
  # nodes above it (timeline rows, suggestion column) are NOT composer children.
  $ed = $n | Where-Object { $_.class -match 'EditText' -and $_.y1 -ge $pane.y1 } | Select-Object -First 1
  $top = if ($ed) { $ed.y1 } else { $pane.y1 }
  "editorTop = $top  (paneTop=$($pane.y1))"
  $kids = $n | Where-Object { $_.y1 -ge $top -and $_.y2 -le $pane.y2 + 1 -and $_.wd -le 1330 -and
    -not ($_.desc -eq 'Open terminal') -and $_.ht -gt 0 }
  $ux1 = ($kids | Measure-Object x1 -Minimum).Minimum
  $ux2 = ($kids | Measure-Object x2 -Maximum).Maximum
  $uy1 = ($kids | Measure-Object y1 -Minimum).Minimum
  $uy2 = ($kids | Measure-Object y2 -Maximum).Maximum
  "union = [$ux1,$uy1][$ux2,$uy2]  (n kids=$($kids.Count))"
  ""
  "readable_dump  = paneH($($pane.ht)) - unionH($($uy2-$uy1)) = $($pane.ht - ($uy2-$uy1))"
  "timelineBox768 = paneH($($pane.ht)) - 48 = $($pane.ht - 48)"
  ""
  "CRITERION 1 (i) dump-side: unionBottom($uy2) <= paneBottom($($pane.y2)) => $($uy2 - $pane.y2)"
  $band = 48
  $pb2 = $pane.y2 - $band
  $ub2 = $uy2 - $band + 12
  "CRITERION 1 (ii) prod-side: unionBottom($ub2) <= paneBottom($pb2) => $($ub2 - $pb2)"
  ""
  "CRITERION 2: unionPx($($uy2-$uy1)) <= band(48) => $($uy2-$uy1 -le 48)"
}

"`n=== ime / nav insets raw ==="
(A shell dumpsys window | Select-String 'type=ime frame|type=navigationBars frame|ROTATION_0=') | ForEach-Object { "  " + $_.ToString().Trim() }

"`n=== CHIP ROW INVENTORY (tool row: y between paneBottom-60 and paneBottom) ==="
if ($pane) {
  $n | Where-Object { $_.y1 -ge ($pane.y2 - 60) -and $_.y2 -le $pane.y2 } | ForEach-Object {
    "  desc=[$($_.desc)] text=[$($_.text)] cls=$($_.class) [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] w=$($_.wd) h=$($_.ht) clk=$($_.clickable)"
  }
}

"`n=== GREP Queue|Steer|× over whole tree ==="
$hits = $n | Where-Object { $_.desc -match 'Queue|Steer' -or $_.text -match 'Queue|Steer' -or $_.desc -eq '×' -or $_.text -eq '×' }
if ($hits) { $hits | ForEach-Object { "  HIT desc=[$($_.desc)] text=[$($_.text)] [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] clk=$($_.clickable)" } }
else { "  (no Queue/Steer/x node anywhere in tree)" }
