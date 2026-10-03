. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir
$x = Cap "c4_diag01"
Say "XML=$x"
$null = IsANR
Say "---- ALL TEXTS y>400 ----"
foreach ($n in (Nodes)) {
  $b = Bnd $n; if (-not $b) { continue }
  if ($b['t'] -lt 400) { continue }
  $t = TxtOf $n; $cl = $n.GetAttribute("class")
  Say ("  [{0},{1}][{2},{3}] {4} cl={5} | {6}" -f $b['l'],$b['t'],$b['r'],$b['b'],$cl,$n.GetAttribute("clickable"),$t)
}
