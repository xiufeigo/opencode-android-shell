$ErrorActionPreference='Continue'
. "$PSScriptRoot\c8lib.ps1"
. "$PSScriptRoot\b8_funcs.ps1"
$PKG='com.xiufeigo.opencodeshell'
$KEYPATH='/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'

Log "=== A: build entries (known_hosts already has 127.0.0.1:2222 => NO verify card expected) ==="
Log "focus=$(Focus) ime=$(ImeOff)"

# nav: home -> Settings -> Servers
function NavPanel {
  if (EnsurePanel) { return $true }
  $x = Dump "NV0"
  $st = FindTxt $x "Settings"
  if ($st) { $c=Center $st; Log "  [Nav] tap Settings @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2500 }
  $sv=$null
  for ($i=1; $i -le 8; $i++) { $x = Dump "NV1"; $sv = FindTxt $x "Servers"; if ($sv) { break }; Swipe 950 745 150 745 400 }
  if (-not $sv) { Log "  [Nav] no Servers tab"; return $false }
  $c=Center $sv; Log "  [Nav] tap Servers @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2500
  return (EnsurePanel) }

# fill form: target, name, and (optional) private key path
function FillForm { param([string]$target,[string]$name,[string]$keypath)
  $x = Dump "FF0"
  $ets = GetFormET $x
  foreach ($e in $ets) { Log "    pre pw=$($e.pw) h=$($e.h) [$($e.b)] val='$($e.val)'" }
  $t = $ets | Sort-Object h -Descending | Select-Object -First 1
  Log "    >>> tap TARGET @ $($t.cx),$($t.cy)"
  TapAt $t.cx $t.cy; Start-Sleep -Milliseconds 900
  $f = GetFormET (Dump "FF1") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
  if (-not $f) { Log "    FAIL target focus"; return $false }
  A @("shell","input","text",$target.Replace(' ','%s')) | Out-Null; Start-Sleep -Milliseconds 1200
  $ok = GetFormET (Dump "FF2") | Where-Object { $_.val -eq $target }
  if (-not $ok) { Log "    FAIL target readback"; return $false }
  Log "    TARGET OK"
  A @("shell","input","keyevent","61") | Out-Null; Start-Sleep -Milliseconds 1000
  $f = GetFormET (Dump "FF3") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
  Log "    TAB1 focus: $(if($f){'pw='+$f.pw+' ['+$f.b+']'}else{'NONE'})"
  if (-not $f -or $f.pw -eq 'true') { Log "    FAIL TAB1"; return $false }
  A @("shell","input","text",$name) | Out-Null; Start-Sleep -Milliseconds 1200
  $ok = GetFormET (Dump "FF4") | Where-Object { $_.val -eq $name }
  if (-not $ok) { Log "    FAIL name readback"; return $false }
  Log "    NAME OK"
  if ($keypath) {
    $found=$false
    for ($k=1; $k -le 6; $k++) {
      A @("shell","input","keyevent","61") | Out-Null; Start-Sleep -Milliseconds 950
      $xx = Dump "FF5k_$k"
      $kk = GetFormET $xx | Where-Object { $_.val -match 'id_ed25519|\.pem|/' -or $_.val -eq $keypath }
      if ($kk) { Log "    found keypath-ish field at TAB$k val='$($kk.val)' [$($kk.b)] pw=$($kk.pw)"; $found=$true; break }
      $f = GetFormET $xx | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
      Log "    TAB$k focus: $(if($f){'pw='+$f.pw+' ['+$f.b+'] val=' + "'" + $f.val + "'"}else{'NONE'})"
    }
    if (-not $found) { Log "    WARN no keypath field found by TAB; dumping form for auth options" }
  }
  return $true }

function CreateEntry { param([string]$name,[string]$target,[string]$keypath)
  Log "  --- create $name (key=$keypath) ---"
  if (-not (NavPanel)) { Log "  FAIL panel unreachable"; return $false }
  $x = Dump "CE0"
  $add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
  if (-not $add) { Log "  FAIL no Add SSH server"; return $false }
  $c=Center $add; Log "  tap Add SSH server @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2200
  $x = Dump "CE1"
  if (-not (FindTxt $x "Add server")) { Log "  FAIL form not open"; return $false }
  if (-not (FillForm $target $name $keypath)) { return $false }
  $x = Dump "CE2"
  $as = FindTxt $x "Add server"
  if (-not $as) { Log "  FAIL no Add server"; return $false }
  $c=Center $as; Log "  tap Add server @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 6000
  $x = Dump "CE3"
  Log "  cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'}) authCard=$(if(AuthCardUp $x){'TRUE'}else{'FALSE'})"
  foreach ($s in (AllStates $x)) { Log "  STATE: $s" }
  Log "  rows=[$((PanelRows $x | ForEach-Object { $_.v }) -join '|')]"
  return $true }

# ---- build 5 ----
$names = @('E2ECHK5554d3e-1','E2ECHK5554d3e-2','E2ECHK5554d3e-3','E2ECHK5554d3e-4','E2ECHK5554d3e-5')
foreach ($n in $names) {
  $kp = $null
  if ($n -eq 'E2ECHK5554d3e-1') { $kp = $KEYPATH }
  $tgt = 'ssh -p 2222 sshtest@127.0.0.1'
  $ok = CreateEntry $n $tgt $kp
  [void](TunnelLen "after_$n")
  if (-not $ok) { Log "  create $n returned FALSE" }
  Shot "A_after_$n"
  if (-not $ok) { Log "  aborting build at $n"; break }
}

Log "=== A: enumerate all rows ==="
[void](EnumRows 'A')
[void](TunnelLen 'after_5build')
Shot 'A_final_panel'
Log "focus=$(Focus)"
Log "=== A DONE ==="
