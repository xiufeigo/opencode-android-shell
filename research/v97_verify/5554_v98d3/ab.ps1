$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c7lib.ps1"

function GetFormET { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) {
    if ($z.class -like '*EditText') {
      $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
      $l=[int]$b.Groups[1].Value; $tp=[int]$b.Groups[2].Value; $bt=[int]$b.Groups[4].Value
      if ($l -eq 138) { $cc=Center $z; $r += [pscustomobject]@{b=$z.bounds; cx=$cc.x; cy=$cc.y; pw=$z.password; val=$z.text; foc=$z.focused; h=($bt-$tp); top=$tp} }
    }
  }; return $r }
function FindTxt { param($xml,[string]$t) return ((Nodes $xml) | Where-Object { $_.text -eq $t -or $_.'content-desc' -eq $t } | Select-Object -First 1) }
function CardUp { param($xml) return [bool]((Nodes $xml) | Where-Object { $_.text -eq 'Verify host identity' }) }
function PanelRows { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    if ($v -match 'E2ECHK5554d3d-') { $c=Center $z; $r += [pscustomobject]@{v=$v; b=$z.bounds; cy=$c.cy} } }
  return $r }
function RowStates { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    if ($v -match 'Connecting|Connected$|Disconnected|Authentication required') { $r += $v } }
  return $r }

# bring the panel to a clean state: no card, no form dialog
function EnsurePanel {
  param([int]$rounds=3)
  for ($k=0; $k -lt $rounds; $k++) {
    $x = Dump "EP$k"
    if (CardUp $x) {
      $cc = $null
      foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1600) { $cc=$c } } }
      if ($cc) { Log "  [EnsurePanel] card up -> tap card Cancel @ $($cc.x),$($cc.y)"; Tap $cc; Start-Sleep -Milliseconds 2000; continue }
    }
    if (FindTxt $x "Add server") {
      $fc=$null
      foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1300) { $fc=$c } } }
      if ($fc) { Log "  [EnsurePanel] form open -> tap footer Cancel @ $($fc.x),$($fc.y)"; Tap $fc; Start-Sleep -Milliseconds 1800; continue }
    }
    $add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
    if ($add) { Log "  [EnsurePanel] panel clean, 'Add SSH server' [$($add.bounds)]"; return $true }
    Log "  [EnsurePanel] round $k: no panel (focus=$(Focus))"
    $st = FindTxt $x "Settings"
    if ($st) { $c=Center $st; Tap $c; Start-Sleep -Milliseconds 2000; $sv=$null
      for ($i=1; $i -le 8; $i++) { $x=Dump "EPs"; $sv=FindTxt $x "Servers"; if ($sv) { break }; Swipe 950 745 150 745 400 }
      if ($sv) { $c=Center $sv; Tap $c; Start-Sleep -Milliseconds 1800 } }
  }
  return $false
}

# full entry creation. returns $true on submit
function CreateEntry { param([string]$name,[string]$pw="keypass123")
  if (-not (EnsurePanel)) { Log "  [CreateEntry] panel unreachable"; return $false }
  $x = Dump "CE0"
  $add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
  if (-not $add) { Log "  [CreateEntry] no Add SSH server"; return $false }
  $c = Center $add; Log "  [CreateEntry] tap Add SSH server @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2000
  $x = Dump "CE1"
  if (-not (FindTxt $x "Add server")) { Log "  [CreateEntry] FAIL form did not open"; return $false }
  # target
  $f = GetFormET $x | Sort-Object top | Select-Object -First 1
  Log "  [CreateEntry] tap target @ $($f.cx),$($f.cy)"; TapAt $f.cx $f.cy; Start-Sleep -Milliseconds 700
  if (-not (GetFormET (Dump "CE2") | Where-Object { $_.foc -eq 'true' })) { Log "  [CreateEntry] FAIL target not focused"; return $false }
  A @("shell","input","text","ssh%s-p%s2222%ssshtest@127.0.0.1") | Out-Null; Start-Sleep -Milliseconds 1100
  if (-not (GetFormET (Dump "CE3") | Where-Object { $_.val -eq 'ssh -p 2222 sshtest@127.0.0.1' })) { Log "  [CreateEntry] FAIL target readback"; return $false }
  # name
  $ok=$false
  for ($i=1; $i -le 8; $i++) {
    $xx = Dump "CE4"
    $cand = GetFormET $xx | Where-Object { $_.pw -eq 'false' -and $_.val -notlike '*sshtest*' -and $_.cy -gt 1150 -and $_.cy -lt 1650 } | Select-Object -First 1
    if ($cand) {
      Log "  [CreateEntry] tap name @ $($cand.cx),$($cand.cy) [$($cand.b)] (try $i)"; TapAt $cand.cx $cand.cy; Start-Sleep -Milliseconds 700
      $fo = GetFormET (Dump "CE5") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
      if ($fo -and $fo.val -notlike '*sshtest*') {
        A @("shell","input","text",$name) | Out-Null; Start-Sleep -Milliseconds 1100
        if (GetFormET (Dump "CE6") | Where-Object { $_.val -eq $name }) { $ok=$true; break }
        Log "  [CreateEntry] name readback miss"
      } else { Log "  [CreateEntry] name tap missed focus" }
    }
    Swipe 540 1600 540 1300 400
  }
  if (-not $ok) { Log "  [CreateEntry] FAIL name"; return $false }
  # password
  $pwok=$false
  for ($i=1; $i -le 9; $i++) {
    $xx = Dump "CE7"
    $cand = GetFormET $xx | Where-Object { $_.pw -eq 'true' -and $_.cy -gt 1150 -and $_.cy -lt 1690 } | Select-Object -First 1
    if ($cand) {
      Log "  [CreateEntry] tap password @ $($cand.cx),$($cand.cy) [$($cand.b)] (try $i)"; TapAt $cand.cx $cand.cy; Start-Sleep -Milliseconds 700
      if (GetFormET (Dump "CE8") | Where-Object { $_.pw -eq 'true' -and $_.foc -eq 'true' }) {
        A @("shell","input","text",$pw) | Out-Null; Start-Sleep -Milliseconds 1100; $pwok=$true; break }
      Log "  [CreateEntry] pw tap missed focus"
    }
    Swipe 540 1600 540 1300 400
  }
  if (-not $pwok) { Log "  [CreateEntry] FAIL password"; return $false }
  $x = Dump "CE9"
  $as = FindTxt $x "Add server"
  if (-not $as) { Log "  [CreateEntry] FAIL no Add server"; return $false }
  $c = Center $as; Log "  [CreateEntry] tap Add server @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 4500
  $x = Dump "CE10"
  Log "  [CreateEntry] cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'}) rows=$(((PanelRows $x) | ForEach-Object { $_.v }) -join '|')"
  return $true
}

# ============================================================
param()
$mode = if ($args.Count -gt 0) { $args[0] } else { "A4" }

if ($mode -eq "A4") {
  Log "########## A4: create entry 2 and press Trust and connect ##########"
  $ok = CreateEntry "E2ECHK5554d3d-2"
  if (-not $ok) { Log "A4: create failed"; exit 1 }
  $x = Dump "A40"
  Log "--- card present? $(if(CardUp $x){'TRUE'}else{'FALSE'}) ---"
  $tc = $null
  foreach ($z in (Nodes $x)) { if ($z.text -like '*Trust and connect*' -or $z.'content-desc' -like '*Trust and connect*') { $tc = Center $z } }
  if ($tc) {
    Log ">>> tap Trust and connect @ $($tc.x),$($tc.y)"
    Shot "A40_card_before_trust"
    Tap $tc
    for ($i=1; $i -le 6; $i++) {
      Start-Sleep -Milliseconds 2500
      $x = Dump "A41_$i"
      $up = CardUp $x
      $st = (RowStates $x)
      $kh = (A @("shell","run-as","com.xiufeigo.opencodeshell","ls","-la","files/ssh/")) -join " "
      Log "  [trust sample $i] cardUp=$up states=[$($st -join ' / ')] rows=[$((PanelRows $x | ForEach-Object { $_.v }) -join '|')]"
      Log "     files/ssh: $kh"
      if ($i -eq 3) { Shot "A41_trust_mid" }
    }
    Shot "A42_trust_after"
    $x = Dump "A43"
    Log "--- final state ---"
    Log "cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'})"
    foreach ($z in (Nodes $x)) { $cc=Center $z; if ($cc.t -gt 1000 -and ($z.text -ne '' -or $z.'content-desc' -ne '')) { Log ("  " + $z.text + " | " + $z.bounds) } }
  } else { Log "A4: Trust and connect not found" }
}
