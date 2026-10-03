$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
$PKG='com.xiufeigo.opencodeshell'
$KEYPATH='/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'

function NavPanel {
  if (EnsurePanel) { return $true }
  $x = Dump "NV0"
  $st = FindTxt $x "Settings"
  if ($st) { $c=Center $st; Log "  [Nav] Settings @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2500 }
  $sv=$null
  for ($i=1; $i -le 8; $i++) { $x = Dump "NV1"; $sv = FindTxt $x "Servers"; if ($sv) { break }; Swipe 950 745 150 745 400 }
  if (-not $sv) { Log "  [Nav] no Servers tab"; return $false }
  $c=Center $sv; Log "  [Nav] Servers @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2500
  return (EnsurePanel) }

# SUBMIT: locate Add server FRESH (footer moves with IME), tap, verify
function Submit { param([string]$name)
  for ($try=1; $try -le 3; $try++) {
    $x = Dump "SB_$name_$try"
    $as = FindTxt $x "Add server"
    if (-not $as) { Log "  [Sub] try${try} no 'Add server' node"; return $false }
    $c = Center $as
    Log "  [Sub] try${try} Add server @ $($c.x),$($c.y) [$($as.bounds)] ime=$(ImeOff)"
    TapAt $c.x $c.y
    Start-Sleep -Milliseconds 5000
    $x = Dump "SBv_$name_$try"
    $hit = $false
    foreach ($r in (PanelRows $x)) { if ($r.v -eq $name) { $hit = $true } }
    $len = TunnelLen "sub_$name_$try"
    Log "  [Sub] try$try after: rowVisible=$hit tunnelLen=$len cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'}) formOpen=$(if(FindTxt $x 'Add server'){'TRUE'}else{'FALSE'})"
    if ($hit -and $len -gt 76) { return $true }
  }
  return $false }

function CreateEntry { param([string]$name,[string]$target,[string]$pw,[switch]$NoPw)
  Log "  --- create $name pw='$pw' ---"
  if (-not (NavPanel)) { Log "  FAIL panel"; return $false }
  $x = Dump "CE0"
  $add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
  if (-not $add) { Log "  FAIL no Add SSH server"; return $false }
  $c=Center $add; Tap $c; Start-Sleep -Milliseconds 2200
  $x = Dump "CE1"
  if (-not (FindTxt $x "Add server")) { Log "  FAIL form not open"; return $false }
  $t = GetFormET $x | Sort-Object h -Descending | Select-Object -First 1
  Log "  target field [$($t.b)] h=$($t.h)"
  TapAt $t.cx $t.cy; Start-Sleep -Milliseconds 900
  $f = GetFormET (Dump "CE2") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
  if (-not $f) { Log "  FAIL target not focused"; return $false }
  SetET $target
  if (-not (GetFormET (Dump "CE3") | Where-Object { $_.val -eq $target })) { Log "  FAIL target readback"; return $false }
  Log "  TARGET OK"
  A @("shell","input","keyevent","61") | Out-Null; Start-Sleep -Milliseconds 1000
  $f = GetFormET (Dump "CE4") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
  if (-not $f -or $f.pw -eq 'true') { Log "  FAIL TAB1 (name)"; return $false }
  SetET $name
  if (-not (GetFormET (Dump "CE5") | Where-Object { $_.val -eq $name })) { Log "  FAIL name readback"; return $false }
  Log "  NAME OK"
  if (-not $NoPw) {
    $pok=$false
    for ($k=1; $k -le 5; $k++) {
      A @("shell","input","keyevent","61") | Out-Null; Start-Sleep -Milliseconds 900
      $f = GetFormET (Dump "CE6k_$k") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
      if ($f -and $f.pw -eq 'true') { $pok=$true; Log "  PW field at TAB$k [$($f.b)]"; break } }
    if ($pok) { SetET $pw; Log "  PW typed" }
    else { Log "  WARN no pw field" } }
  return (Submit $name) }

Log "=== A: build 5 entries ==="
$base = TunnelLen 'A_base'
Log "BASELINE tunnel len=$base"
$specs = @(
  @{n='E2ECHK5554d3e-1'; t='ssh -p 2222 sshtest@127.0.0.1'; p='keypass123'},
  @{n='E2ECHK5554d3e-2'; t='ssh -p 2222 sshtest@127.0.0.1'; p='keypass123'},
  @{n='E2ECHK5554d3e-3'; t='ssh -p 2222 sshtest@127.0.0.1'; p='keypass123'},
  @{n='E2ECHK5554d3e-4'; t='ssh -p 2222 sshtest@127.0.0.1'; p='keypass123'},
  @{n='E2ECHK5554d3e-5'; t='ssh -p 2222 sshtest@127.0.0.1'; p='keypass123'}
)
foreach ($s in $specs) {
  $ok = CreateEntry $s.n $s.t $s.p
  Shot "A_$($s.n)"
  if (-not $ok) { Log "  ABORT at $($s.n)"; break } }
Log "=== A: enumerate ==="
[void](EnumRows 'A')
[void](TunnelLen 'A_done')
Shot 'A_panel'
Log "=== A DONE focus=$(Focus) ==="


