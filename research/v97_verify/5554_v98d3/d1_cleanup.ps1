$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
. "$PSScriptRoot\dv.ps1"
. "$PSScriptRoot\c8_funcs.ps1"
$PKG='com.xiufeigo.opencodeshell'

Log "########## D: cleanup ##########"
# delete every self-built entry, multi-pass
for ($pass=1; $pass -le 4; $pass++) {
  $x = Dump "CL_p$pass"
  $rows = @(); foreach ($r in (PanelRows $x)) { $rows += $r.v }
  $all=@($rows)
  for ($s=0; $s -lt 6; $s++) { Swipe 500 1800 500 1200 350; $x = Dump "CL_p${pass}s$s"; foreach ($r in (PanelRows $x)) { $all += $r.v } }
  $all = ($all | Sort-Object -Unique)
  Log "CL pass${pass} rows=[$($all -join '|')] count=$($all.Count)"
  if ($all.Count -eq 0) { break }
  foreach ($n in $all) {
    Log "  [CL] deleting $n"
    $r = DelVerified $n
    Log "  [CL] $n -> ok=$($r.ok) gone=$($r.gone)"
    if (-not $r.ok) { break } }
}
$x = Dump 'CL_final'
$fin=@(); foreach ($r in (PanelRows $x)) { $fin += $r.v }
for ($s=0; $s -lt 6; $s++) { Swipe 500 1800 500 1200 350; $x = Dump "CL_fs$s"; foreach ($r in (PanelRows $x)) { $fin += $r.v } }
$fin = ($fin | Sort-Object -Unique)
Log "CLEANUP FINAL rows=[$($fin -join '|')] count=$($fin.Count)"
# counter-example: other people's entry intact?
$other=$false; foreach ($z in (Nodes (Dump 'CL_other'))) { $v=if($z.text){$z.text}else{$z.'content-desc'}; if ($v -match '10\.0\.2\.2:4095') { $other=$true; Log "  OTHER '10.0.2.2:4095' [$($z.bounds)] PRESENT" } }
Log "OTHER_ENTRY_INTACT=$other"
[void](TunnelLen 'CL_final')
Shot 'CL_final_panel'
# app private dir residue
$hit = A @("shell","run-as",$PKG,"grep","-rl","E2ECHK5554d3e",".")
Log "grep E2ECHK5554d3e -> $(if($hit){$hit -join ' '}else{'(empty)'})"
# known_hosts BEFORE removing (evidence of what we clear)
Log "--- known_hosts BEFORE removal ---"
A @("shell","run-as",$PKG,"ls","-la","files/ssh/") | ForEach-Object { Log "  $_" }
A @("shell","run-as",$PKG,"cat","files/ssh/known_hosts") | ForEach-Object { Log "  $_" }
A @("shell","run-as",$PKG,"rm","-f","files/ssh/known_hosts") | Out-Null
Log "--- known_hosts AFTER removal ---"
$after = A @("shell","run-as",$PKG,"ls","-la","files/ssh/")
Log "  $($after -join ' ')"
$kh = A @("shell","run-as",$PKG,"cat","files/ssh/known_hosts")
Log "  cat known_hosts -> $(if($kh){$kh -join ' '}else{'(absent - removed)'})"
# sdcard residue
$sd = A @("shell","ls","/sdcard/_*.xml")
Log "SDCARD _*.xml: $(if($sd){$sd -join ' '}else{'(none)'})"
$mine = A @("shell","ls","/sdcard/") | Where-Object { $_ -match '^(A_|B_|C1|C2|C3|D0|EN_|DL_|SB|CE|SB_|CL_)' }
Log "SDCARD my-prefixed leftovers: $(if($mine){$mine -join ' '}else{'(none)'})"
foreach ($m in $mine) { A @("shell","rm","-f","/sdcard/$m") | Out-Null; Log "  rm -f /sdcard/$m" }
$rev = A @("reverse","--list")
Log "REVERSE LIST: $($rev -join ' | ')"
Log "focus=$(Focus)"
Log "########## D DONE ##########"

