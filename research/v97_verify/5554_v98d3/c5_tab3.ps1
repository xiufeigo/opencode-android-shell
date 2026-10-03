. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c5lib.ps1

function TabLabels($doc) {
  $r = @()
  foreach ($n in (AllNodes $doc)) {
    $t = NText $n; if (-not $t) { continue }
    $b = NBnd $n; if ($null -eq $b) { continue }
    if ($b.T -ge 700 -and $b.T -le 790) { $r += ,@($t,$b.L,$b.T,$b.R,$b.B) }
  }
  return @($r)
}

$doc = Dump 'tbx_init'
for ($i=0; $i -lt 5; $i++) {
  $srv = FindByLabel $doc 'Servers'
  if (@($srv).Count -gt 0) {
    $b = NBnd @($srv)[0]
    "TAP Servers @($([int](($b.L+$b.R)/2)),$([int](($b.T+$b.B)/2))) (swipes=$i)"
    TapXY ([int](($b.L+$b.R)/2)) ([int](($b.T+$b.B)/2))
    Start-Sleep -Seconds 2
    break
  }
  $doc = Dump ("tbx{0}" -f $i)
  "---- pass $i tabs ----"
  foreach ($x in (TabLabels $doc)) { "  {0} [{1},{2}][{3},{4}]" -f $x[0],$x[1],$x[2],$x[3],$x[4] }
  A @('shell','input','swipe','980','744','200','744','400') | Out-Null
  Start-Sleep -Milliseconds 900
}

if (@($srv).Count -eq 0) { "STILL_NO_SERVERS"; Cap 'tb_fail2'; exit 1 }

$doc = Dump srv0
"==== SERVERS PANEL ===="
foreach ($b in (ScrollableBounds $doc)) { "  SV [{0},{1}][{2},{3}]" -f $b.L,$b.T,$b.R,$b.B }
"---- kebabs ----"
foreach ($k in (KebabList $doc)) { "  KEB [{0},{1}][{2},{3}] title='{4}'" -f $k.Bnd.L,$k.Bnd.T,$k.Bnd.R,$k.Bnd.B,(RowTitleFor $doc $k) }
"---- text below y=800 ----"
foreach ($n in (AllNodes $doc)) {
  $t = NText $n; if (-not $t) { continue }
  $b = NBnd $n; if ($null -eq $b) { continue }
  if ($b.T -lt 800) { continue }
  "  T [{0},{1}][{2},{3}] clk={4} {5}" -f $b.L,$b.T,$b.R,$b.B,(NClk $n),$t
}
