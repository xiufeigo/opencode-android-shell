. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c5lib.ps1

# Navigate: home -> Settings -> Servers tab
$doc = Dump nav0
$set = FindByLabel $doc 'Settings'
if (@($set).Count -eq 0) {
  "NAV_SETTINGS_NOT_FOUND"
  foreach ($n in (AllNodes $doc)) { $t=NText $n; if ($t) { $b=NBnd $n; "  T [{0},{1}][{2},{3}] {4}" -f $b.L,$b.T,$b.R,$b.B,$t } }
  exit 1
}
$b = NBnd @($set)[0]
"NAV tap Settings @($([int](($b.L+$b.R)/2)),$([int](($b.T+$b.B)/2)))"
TapXY ([int](($b.L+$b.R)/2)) ([int](($b.T+$b.B)/2))
Start-Sleep -Seconds 2

$doc = Dump nav1
"==== settings tabs ===="
foreach ($n in (AllNodes $doc)) {
  $t = NText $n; $d = NDesc $n
  if (-not $t -and -not $d) { continue }
  $bb = NBnd $n; if ($null -eq $bb) { continue }
  "  [{0},{1}][{2},{3}] clk={4} {5} | {6}" -f $bb.L,$bb.T,$bb.R,$bb.B,(NClk $n),(NCls $n),$(if($t){$t}else{"«$d»"})
}
