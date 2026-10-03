. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c5lib.ps1

$tag = $args[0]
$doc = Dump $tag
$nodes = AllNodes $doc

"==== [$tag] FOCUS ===="
(Sh dumpsys window | Select-String 'mCurrentFocus').ToString().Trim()
"==== [$tag] SCROLLABLE ===="
foreach ($b in (ScrollableBounds $doc)) { "  SV [{0},{1}][{2},{3}]" -f $b.L,$b.T,$b.R,$b.B }
"==== [$tag] GROUPS ===="
foreach ($g in (GroupHeadings $doc)) { "  GRP $g" }
"==== [$tag] KEBABS (row anchors) ===="
$k = KebabList $doc
foreach ($x in $k) {
  "  KEB [{0},{1}][{2},{3}] @({4},{5})  title='{6}'" -f $x.Bnd.L,$x.Bnd.T,$x.Bnd.R,$x.Bnd.B,$x.CX,$x.CY,(RowTitleFor $doc $x)
}
"==== [$tag] ALL TEXT/DESC NODES ===="
$i=0
foreach ($n in $nodes) {
  $t = NText $n; $d = NDesc $n
  if (-not $t -and -not $d) { continue }
  $b = NBnd $n
  if ($null -eq $b) { continue }
  $lbl = if ($t) { $t } else { "«desc:$d»" }
  "  #{0,3} [{1},{2}][{3},{4}] en={5} clk={6} scr={7} {8} | {9}" -f $i,$b.L,$b.T,$b.R,$b.B,(NEn $n),(NClk $n),(NScr $n),(NCls $n),$lbl
  $i++
}
