$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
. "$PSScriptRoot\dv.ps1"
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
# fill form and submit; -$skipSubmit leaves the entry in its auto-connect window
function FillSubmit { param([string]$name,[string]$target,[string]$pw,[switch]$SkipSubmit)
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
  if ($SkipSubmit) { return $true }
  for ($try=1; $try -le 3; $try++) {
    $x = Dump "SB_$name_$try"
    $as = FindTxt $x "Add server"
    if (-not $as) { Log "  [Sub] no Add server node"; return $false }
    $c = Center $as; Log "  [Sub] try${try} Add server @ $($c.x),$($c.y) [$($as.bounds)]"; TapAt $c.x $c.y
    Start-Sleep -Milliseconds 5000
    $x = Dump "SBv_$name_$try"
    $hit=$false; foreach ($r in (PanelRows $x)) { if ($r.v -eq $name) { $hit=$true } }
    $len = TunnelLen "sub_$name"
    Log "  [Sub] try${try} rowVisible=$hit tunnelLen=$len"
    if ($hit -and $len -gt 76) { return $true } }
  return $false }

# ===== C2: Connecting (unreachable target, delete inside the Connecting window) =====
Log "########## C2: Connecting ##########"
[void](TunnelLen 'C2_before')
$UNREACH = 'ssh -p 2222 sshtest@10.255.255.1'
Log "C2 target = $UNREACH (blackhole IP; adb reverse untouched)"
$ok = FillSubmit 'E2ECHK5554d3e-c2' $UNREACH 'keypass123' -SkipSubmit
Log "C2 form filled=$ok"
# submit then IMMEDIATELY hunt for the row's kebab while it is still Connecting
$x = Dump 'C2_pre'
$as = FindTxt $x "Add server"
$c = Center $as
Log "C2 tap Add server @ $($c.x),$($c.y) then IMMEDIATELY scan for Connecting"
TapAt $c.x $c.y
$kebab=$null; $st=''
for ($i=1; $i -le 8; $i++) {
  $x = Dump "C2_s${i}"
  $st = StateOf $x 'E2ECHK5554d3e-c2'
  $ks = @(); foreach ($z in (Nodes $x)) { if ($z.'content-desc' -eq 'Server actions') { $cc=Center $z; $ks += [pscustomobject]@{x=$cc.x;y=$cc.y;clk=$z.clickable} } }
  Log "C2 scan ${i}: state='$st' kebabs=$($ks.Count) clk=$(if($ks.Count){$ks[0].clk}else{'-'})"
  Shot "C2_scan${i}"
  if ($ks.Count -gt 0 -and $st -match 'Connecting') { $kebab=$ks[0]; Log "C2 GOT Connecting window + kebab @ $($kebab.x),$($kebab.y)"; break }
  if ($kebab -eq $null -and $ks.Count -gt 0) { $kebab=$ks[0] }
  Start-Sleep -Milliseconds 700 }
Log "C2 CAPTURED state='$st' kebabFound=$($kebab -ne $null)"
$x = Dump 'C2_before_del'
Log "C2 states before delete: $((AllStates $x) -join ' // ')"
[void](TunnelLen 'C2_connected_state')
if ($kebab) {
  Log "C2 tap kebab @ $($kebab.x),$($kebab.y)"
  TapAt $kebab.x $kebab.y; Start-Sleep -Milliseconds 1200
  $x = Dump 'C2_menu'
  $del=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Delete' -or $z.'content-desc' -eq 'Delete') { $del=$z } }
  if ($del) { $dc=Center $del; Log "C2 menu Delete [$($del.bounds)] tap @ $($dc.x),$($dc.y)"; TapAt $dc.x $dc.y; Start-Sleep -Milliseconds 1500
    $x = Dump 'C2_confirm'
    $t1 = FindTxt $x "Delete SSH server?"; Log "C2 confirm title present=$($t1 -ne $null)"
    $cf=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Delete' -or $z.'content-desc' -eq 'Delete') { $cc=Center $z; if ($cc.y -gt 1700) { $cf=$cc } } }
    if ($cf) { Log "C2 CONFIRM @ $($cf.x),$($cf.y)"; Shot 'C2_confirm'; TapAt $cf.x $cf.y; Start-Sleep -Milliseconds 2500
      $x = Dump 'C2_after'
      $gone = -not (($x) -match [regex]::Escape('E2ECHK5554d3e-c2'))
      Log "C2 DELETE DONE gone=$gone rows=[$((PanelRows $x | ForEach-Object { $_.v }) -join '|')]"
      [void](TunnelLen 'C2_after_del'); Shot 'C2_deleted' } else { Log "C2 no confirm button" } }
  else { Log "C2 no Delete menu item" } }
else { Log "C2 NO KEBAB during Connecting -> cannot open delete menu (record honestly)" }
Log "########## C2 DONE ##########"

# ===== C3: Authentication required (wrong password) =====
Log "########## C3: Authentication required ##########"
$ok = FillSubmit 'E2ECHK5554d3e-c3' 'ssh -p 2222 sshtest@127.0.0.1' 'wrongpass'
Log "C3 create ok=$ok"
$st=''
for ($i=1; $i -le 8; $i++) {
  Start-Sleep -Seconds 2
  $x = Dump "C3_s${i}"
  $st = StateOf $x 'E2ECHK5554d3e-c3'
  Log "C3 sample ${i}: state='$st' authCard=$(AuthCardUp $x) cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'})"
  if ($st -match 'Authentication required') { break }
  if ($st -eq 'Connected') { break } }
Log "C3 FINAL STATE = '$st'"
Shot 'C3_state'
[void](TunnelLen 'C3_state')
$r = DelVerified 'E2ECHK5554d3e-c3'
Log "C3 DELETE ok=$($r.ok) gone=$($r.gone) rowsAfter=[$($r.rowsAfter)]"
[void](TunnelLen 'C3_after_del')
Shot 'C3_deleted'
Log "########## C3 DONE ##########"


