$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
. "$PSScriptRoot\dv.ps1"
$PKG='com.xiufeigo.opencodeshell'

Log "########## D3: top-down delete loop until tunnel == 76 ##########"
$base = 76
for ($iter=1; $iter -le 10; $iter++) {
  $len = TunnelLen "D3_i$iter"
  Log "D3 iter${iter} tunnel=$len (base=$base)"
  if ($len -le $base) { Log "D3 tunnel back to baseline"; break }
  [void](EnsurePanel)
  ScrollTop 4
  # collect visible rows, take the first
  $x = Dump "D3_v$iter"
  $vis = @(); foreach ($r in (PanelRows $x)) { $vis += $r }
  Log "D3 iter${iter} visible rows: $(($vis | ForEach-Object { $_.v }) -join '|')  (count=$($vis.Count))"
  if ($vis.Count -eq 0) {
    # maybe scrolled past everything: scroll down once and retry
    Swipe 500 1900 500 1100 400
    $x = Dump "D3_v2$iter"
    foreach ($r in (PanelRows $x)) { $vis += $r }
    Log "D3 iter${iter} after scroll-down: $(($vis | ForEach-Object { $_.v }) -join '|')"
    if ($vis.Count -eq 0) { Log "D3 iter${iter} NO ROWS VISIBLE - give up this iter"; continue } }
  $target = $vis[0].v
  Log "D3 iter${iter} deleting FIRST visible row: $target"
  $r = DelVerified $target
  Log "D3 iter${iter} $target -> ok=$($r.ok) gone=$($r.gone)"
  if (-not $r.ok) { Log "D3 iter${iter} delete failed; trying next visible"; if ($vis.Count -gt 1) { $t2=$vis[1].v; $r2=DelVerified $t2; Log "D3 $t2 -> ok=$($r2.ok)" } }
}
$final = TunnelLen 'D3_final'
Log "D3 FINAL tunnel=$final"
$x = Dump 'D3_finalview'
$fin=@(); foreach ($r in (PanelRows $x)) { $fin += $r.v }
Log "D3 FINAL visible rows=[$($fin -join '|')] count=$($fin.Count)"
$other=$false; foreach ($z in (Nodes (Dump 'D3_other'))) { $v=if($z.text){$z.text}else{$z.'content-desc'}; if ($v -match '10\.0\.2\.2:4095') { $other=$true; Log "  OTHER 10.0.2.2:4095 [$($z.bounds)] PRESENT" } }
Log "D3 OTHER_ENTRY_INTACT=$other"
Shot 'D3_final_panel'
Log "########## D3 DONE ##########"
