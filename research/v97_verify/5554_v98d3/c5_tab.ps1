. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c5lib.ps1

# scroll the settings tab strip horizontally to reveal the Servers tab
$doc = Dump tab0
$tabs = FindTextContains $doc 'Shortcuts'
if (@($tabs).Count -eq 0) { "NO_TABS"; exit 1 }
$b = NBnd @($tabs)[0]
# swipe leftwards inside the tab strip row to move to later tabs
FastScroll 900 ([int](($b.T+$b.B)/2)) 200
$doc = Dump tab1
"==== tab strip after scroll ===="
foreach ($n in (AllNodes $doc)) {
  $t = NText $n
  if (-not $t) { continue }
  $bb = NBnd $n
  if ($null -eq $bb) { continue }
  if ($bb.T -ge 700 -and $bb.T -le 790) { "  TAB [{0},{1}][{2},{3}] {4}" -f $bb.L,$bb.T,$bb.R,$bb.B,$t }
}
"==== any 'Server' text anywhere ===="
foreach ($n in (AllNodes $doc)) {
  $t = NText $n
  if ($t -and $t.Contains('Server')) { $bb=NBnd $n; "  SRV [{0},{1}][{2},{3}] {4}" -f $bb.L,$bb.T,$bb.R,$bb.B,$t }
}
