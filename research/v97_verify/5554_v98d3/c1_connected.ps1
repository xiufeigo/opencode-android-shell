$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
. "$PSScriptRoot\c8_funcs.ps1"

function NavPanel {
  if (EnsurePanel) { return $true }
  $x = Dump "NV0"
  $st = FindTxt $x "Settings"
  if ($st) { $c=Center $st; Tap $c; Start-Sleep -Milliseconds 2500 }
  $sv=$null
  for ($i=1; $i -le 8; $i++) { $x = Dump "NV1"; $sv = FindTxt $x "Servers"; if ($sv) { break }; Swipe 950 745 150 745 400 }
  if (-not $sv) { return $false }
  $c=Center $sv; Tap $c; Start-Sleep -Milliseconds 2500
  return (EnsurePanel) }
function Submit { param([string]$name)
  for ($try=1; $try -le 3; $try++) {
    $x = Dump "SB_$name_$try"
    $as = FindTxt $x "Add server"
    if (-not $as) { Log "  [Sub] no Add server node"; return $false }
    $c = Center $as; Log "  [Sub] try$try Add server @ $($c.x),$($c.y) [$($as.bounds)]"; TapAt $c.x $c.y
    Start-Sleep -Milliseconds 5000
    $x = Dump "SBv_$name_$try"
    $hit=$false; foreach ($r in (PanelRows $x)) { if ($r.v -eq $name) { $hit=$true } }
    $len = TunnelLen "sub_$name_$try"
    Log "  [Sub] try$try rowVisible=$hit tunnelLen=$len"
    if ($hit -and $len -gt 76) { return $true } }
  return $false }
function CreateEntry { param([string]$name,[string]$target,[string]$pw)
  Log "  --- create $name target='$target' pw='$pw' ---"
  if (-not (NavPanel)) { Log "  FAIL panel"; return $false }
  $x = Dump "CE0"
  $add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
  if (-not $add) { Log "  FAIL no Add SSH server"; return $false }
  $c=Center $add; Tap $c; Start-Sleep -Milliseconds 2200
  $x = Dump "CE1"
  if (-not (FindTxt $x "Add server")) { Log "  FAIL form not open"; return $false }
  $t = GetFormET $x | Sort-Object h -Descending | Select-Object -First 1
  TapAt $t.cx $t.cy; Start-Sleep -Milliseconds 900
  $f = GetFormET (Dump "CE2") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
  if (-not $f) { Log "  FAIL target not focused"; return $false }
  SetET $target
  if (-not (GetFormET (Dump "CE3") | Where-Object { $_.val -eq $target })) { Log "  FAIL target readback"; return $false }
  Log "  TARGET OK"
  A @("shell","input","keyevent","61") | Out-Null; Start-Sleep -Milliseconds 1000
  $f = GetFormET (Dump "CE4") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
  if (-not $f -or $f.pw -eq 'true') { Log "  FAIL TAB1 name"; return $false }
  SetET $name
  if (-not (GetFormET (Dump "CE5") | Where-Object { $_.val -eq $name })) { Log "  FAIL name readback"; return $false }
  Log "  NAME OK"
  $pok=$false
  for ($k=1; $k -le 5; $k++) {
    A @("shell","input","keyevent","61") | Out-Null; Start-Sleep -Milliseconds 900
    $f = GetFormET (Dump "CE6k_$k") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
    if ($f -and $f.pw -eq 'true') { $pok=$true; Log "  PW at TAB$k"; break } }
  if ($pok) { SetET $pw; Log "  PW typed" } else { Log "  WARN no pw field" }
  return (Submit $name) }

# ============ C1: Connected ============
Log "########## C1: Connected ##########"
[void](TunnelLen 'C1_before')
$ok = CreateEntry 'E2ECHK5554d3e-c1' 'ssh -p 2222 sshtest@127.0.0.1' 'opencode'
Log "C1 create ok=$ok"
$x = Dump 'C1_a'
Log "C1 rows=[$((PanelRows $x | ForEach-Object { $_.v }) -join '|')]"
foreach ($s in (AllStates $x)) { Log "C1 STATE: $s" }
Shot 'C1_created'
# wait for connect to settle
for ($i=1; $i -le 6; $i++) {
  Start-Sleep -Seconds 3
  $x = Dump "C1_w$i"
  $st = StateOf $x 'E2ECHK5554d3e-c1'
  $auth = AuthCardUp $x
  Log "C1 sample ${i} state='$st' authCard=$auth cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'})"
  if ($st -eq 'Connected') { break }
  if ($auth) {
    Log "C1 >> auth card up, trying keypass123 then opencode"
    [void](AnswerAuth 'keypass123')
    Start-Sleep -Seconds 3
    $x = Dump "C1_afterkey"
    Log "C1 after keypass123: state='$(StateOf $x 'E2ECHK5554d3e-c1')'"
    if ((StateOf $x 'E2ECHK5554d3e-c1') -ne 'Connected' -and (AuthCardUp $x)) {
      [void](AnswerAuth 'opencode'); Start-Sleep -Seconds 3
      $x = Dump "C1_afteropen"
      Log "C1 after opencode: state='$(StateOf $x 'E2ECHK5554d3e-c1')'" } } }
$x = Dump 'C1_final'
$st = StateOf $x 'E2ECHK5554d3e-c1'
Log "C1 FINAL STATE = '$st'"
Shot 'C1_connected'
[void](TunnelLen 'C1_connected')
if ($st -eq 'Connected') {
  $r = DeleteRow 'E2ECHK5554d3e-c1'
  Log "C1 DELETE ok=$($r.ok) gone=$($r.gone) rowsAfter=[$($r.rowsAfter)]"
  [void](TunnelLen 'C1_after_del')
  Shot 'C1_deleted' }
Log "########## C1 DONE ##########"


