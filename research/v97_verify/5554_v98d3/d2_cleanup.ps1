$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
. "$PSScriptRoot\dv.ps1"
$PKG='com.xiufeigo.opencodeshell'

Log "########## D2: real cleanup (restart app to clear stuck auth card) ##########"
[void](TunnelLen 'D2_before')
A @("shell","am","force-stop",$PKG) | Out-Null
Start-Sleep -Seconds 3
A @("shell","am","start","-n","$PKG/.OcActivity") | Out-Null
Start-Sleep -Seconds 7
Log "focus=$(Focus)"
# nav
$x = Dump 'D2_0'
$st = FindTxt $x "Settings"
if ($st) { $c=Center $st; Tap $c; Start-Sleep -Milliseconds 2500 }
$sv=$null
for ($i=1; $i -le 8; $i++) { $x = Dump 'D2_1'; $sv = FindTxt $x "Servers"; if ($sv) { break }; Swipe 950 745 150 745 400 }
if ($sv) { $c=Center $sv; Log "tap Servers @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2500 }
[void](EnsurePanel)
ScrollTop 4
# full row sweep: scroll down through the whole list collecting names
$all=@()
for ($i=0; $i -lt 12; $i++) {
  $x = Dump "D2_sw$i"
  foreach ($r in (PanelRows $x)) { $all += $r.v }
  foreach ($r in (PanelRows $x)) { Log "  visible@${i}: $($r.v) [$($r.b)]" }
  $n0 = $all.Count
  Swipe 500 1900 500 1100 400
  $x2 = Dump "D2_swc$i"
  $new=0; foreach ($r in (PanelRows $x2)) { if ($all -notcontains $r.v) { $new++ ; $all += $r.v } }
  if ($new -eq 0 -and $i -gt 2) { Log "  no new rows after scroll ${i}"; break } }
$all = ($all | Sort-Object -Unique)
Log "D2 SWEEP rows=[$($all -join '|')] count=$($all.Count)"
[void](TunnelLen 'D2_swept')

# delete them all
for ($pass=1; $pass -le 5; $pass++) {
  $todel = @()
  for ($i=0; $i -lt 10; $i++) {
    $x = Dump "D2_p${pass}_$i"
    foreach ($r in (PanelRows $x)) { $todel += $r.v }
    $new=0; $y2 = Dump "D2_p${pass}_c$i"; foreach ($r in (PanelRows $y2)) { if ($todel -notcontains $r.v) { $new++ } }
    Swipe 500 1900 500 1100 400
    if ($new -eq 0 -and $i -gt 2) { break } }
  $todel = ($todel | Sort-Object -Unique)
  Log "D2 pass${pass} todel=[$($todel -join '|')] count=$($todel.Count)"
  if ($todel.Count -eq 0) { break }
  foreach ($n in $todel) {
    $r = DelVerified $n
    Log "D2 del $n -> ok=$($r.ok) gone=$($r.gone)"
    if (-not $r.ok) { Log "D2 could not delete $n"; break }
    ScrollTop 3 } }
[void](TunnelLen 'D2_after')
$x = Dump 'D2_final'
$fin=@(); foreach ($r in (PanelRows $x)) { $fin += $r.v }
Log "D2 FINAL visible rows=[$($fin -join '|')]"
$other=$false; foreach ($z in (Nodes (Dump 'D2_other'))) { $v=if($z.text){$z.text}else{$z.'content-desc'}; if ($v -match '10\.0\.2\.2:4095') { $other=$true; Log "  OTHER 10.0.2.2:4095 [$($z.bounds)] PRESENT" } }
Log "D2 OTHER_ENTRY_INTACT=$other"
Shot 'D2_final_panel'
Log "########## D2 DONE ##########"
