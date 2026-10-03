$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
. "$PSScriptRoot\dv.ps1"
Log "########## D4: reach the 2 remaining rows ##########"
[void](TunnelLen 'D4_0')
[void](EnsurePanel)
ScrollTop 4
for ($i=0; $i -lt 14; $i++) {
  $x = Dump "D4_$i"
  $v=@(); foreach ($r in (PanelRows $x)) { $v += $r.v }
  Log "D4 step${i}: rows=[$($v -join '|')]"
  $all=@(); foreach ($z in (Nodes $x)) { $t=if($z.text){$z.text}else{$z.'content-desc'}; if ($t -match 'E2ECHK5554d3e|10\.0\.2\.2:4095|Connecting|Authentication') { $all += "$t{$($z.bounds)}" } }
  foreach ($a in ($all | Sort-Object -Unique)) { Log "     $a" }
  Swipe 540 2000 540 900 500 }
$last = TunnelLen 'D4_end'
Log "D4 tunnel=$last"
Log "########## D4 DONE ##########"
