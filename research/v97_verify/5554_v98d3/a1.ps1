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
function LogET { param($xml,[string]$tag) foreach ($e in (GetFormET $xml)) { Log ("  $tag pw=" + $e.pw + " h=" + $e.h + " [" + $e.b + "] c=" + $e.cx + "," + $e.cy + " foc=" + $e.foc + " val='" + $e.val + "'") } }

Log "=== ensure Servers panel ==="
$x = Dump "T00"
$add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
if (-not $add) {
  $st = FindTxt $x "Settings"
  if ($st) { $c=Center $st; Tap $c; Start-Sleep -Milliseconds 2000; $sv=$null
    for ($i=1; $i -le 8; $i++) { $x=Dump "T01"; $sv=FindTxt $x "Servers"; if ($sv) { break }; Swipe 950 745 150 745 400 }
    if ($sv) { $c=Center $sv; Tap $c; Start-Sleep -Milliseconds 2000 }
    $x = Dump "T02"
    $add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1 }
}
if (-not $add) { Log "ABORT: cannot reach Add SSH server (focus=$(Focus))"; exit 1 }
$c = Center $add; Log "tap Add SSH server @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2000
$x = Dump "T03"
if (-not (FindTxt $x "Add server")) { Log "ABORT: form did not open"; exit 1 }
Log "form OPEN"

# ---- TARGET: topmost form EditText ----
LogET $x "T-pre"
$f = GetFormET $x | Sort-Object top | Select-Object -First 1
Log ">>> tap TARGET @ $($f.cx),$($f.cy) [$($f.b)]"
TapAt $f.cx $f.cy; Start-Sleep -Milliseconds 800
$foc = GetFormET (Dump "T04") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
if (-not $foc) { Log "ABORT: target not focused"; exit 1 }
A @("shell","input","text","ssh%s-p%s2222%ssshtest@127.0.0.1") | Out-Null; Start-Sleep -Milliseconds 1200
$tb = GetFormET (Dump "T05") | Where-Object { $_.val -eq 'ssh -p 2222 sshtest@127.0.0.1' } | Select-Object -First 1
if (-not $tb) { LogET (Dump "T05b") "T-rb"; Log "ABORT: target readback mismatch"; exit 1 }
Log "TARGET OK [$($tb.b)]"
Shot "T05_target"

# ---- NAME: form ET that is not the target, not password ----
$nameDone=$false
for ($i=1; $i -le 8; $i++) {
  $x = Dump "T06"
  $cand = GetFormET $x | Where-Object { $_.pw -eq 'false' -and $_.val -notlike '*sshtest*' -and $_.cy -gt 1150 -and $_.cy -lt 1650 } | Select-Object -First 1
  if ($cand) {
    Log ">>> tap NAME @ $($cand.cx),$($cand.cy) [$($cand.b)] h=$($cand.h) (try $i)"
    TapAt $cand.cx $cand.cy; Start-Sleep -Milliseconds 800
    $foc2 = GetFormET (Dump "T07") | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1
    if ($foc2 -and $foc2.val -notlike '*sshtest*') {
      A @("shell","input","text","E2ECHK5554d3d-1") | Out-Null; Start-Sleep -Milliseconds 1200
      $hit = GetFormET (Dump "T08") | Where-Object { $_.val -eq 'E2ECHK5554d3d-1' } | Select-Object -First 1
      if ($hit) { Log "NAME OK [$($hit.b)]"; $nameDone=$true; break }
      $bad = GetFormET (Dump "T08b") | Where-Object { $_.val -like '*sshtest*' } | Select-Object -First 1
      Log "name readback failed; target='$($bad.val)'"
    } else { Log "name tap missed focus (foc='$($foc2.val)')" }
  }
  Swipe 540 1600 540 1300 400
}
if (-not $nameDone) { Log "ABORT: name not filled"; exit 1 }
Shot "T09_name"

# ---- PASSWORD ----
$pwDone=$false
for ($i=1; $i -le 9; $i++) {
  $x = Dump "T10"
  $cand = GetFormET $x | Where-Object { $_.pw -eq 'true' -and $_.cy -gt 1150 -and $_.cy -lt 1690 } | Select-Object -First 1
  if ($cand) {
    Log ">>> tap PASSWORD @ $($cand.cx),$($cand.cy) [$($cand.b)] (try $i)"
    TapAt $cand.cx $cand.cy; Start-Sleep -Milliseconds 800
    $foc3 = GetFormET (Dump "T11") | Where-Object { $_.pw -eq 'true' -and $_.foc -eq 'true' } | Select-Object -First 1
    if ($foc3) { A @("shell","input","text","keypass123") | Out-Null; Start-Sleep -Milliseconds 1200; Log "PASSWORD focused+filled"; $pwDone=$true; break }
    Log "pw tap missed focus"
  }
  Swipe 540 1600 540 1300 400
}
if (-not $pwDone) { Log "ABORT: password not filled"; exit 1 }
Shot "T12_pw"

$x = Dump "T13"
$as = FindTxt $x "Add server"
if (-not $as) { Log "ABORT: Add server not found"; exit 1 }
$c = Center $as; Log ">>> tap Add server @ $($c.x),$($c.y)"; Tap $c
Start-Sleep -Milliseconds 4500
$x = Dump "T14"
$card = (Nodes $x) | Where-Object { $_.text -eq 'Verify host identity' }
Log "=== cardUp=$(if($card){'TRUE'}else{'FALSE'}) ==="
foreach ($z in (Nodes $x)) { $cc = Center $z; if ($cc.t -gt 1000 -and ($z.text -ne '' -or $z.'content-desc' -ne '')) { Log ("  " + ($z.text) + " | cd=" + $z.'content-desc' + " | clk=" + $z.clickable + " | " + $z.bounds) } }
Shot "T14_card"
