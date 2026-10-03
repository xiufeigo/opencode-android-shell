$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c7lib.ps1"

function GetFormET { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { if ($z.class -like '*EditText') { $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
    if ([int]$b.Groups[1].Value -eq 138) { $cc=Center $z; $r += [pscustomobject]@{b=$z.bounds; cx=$cc.x; cy=$cc.y; pw=$z.password; val=$z.text; foc=$z.focused; h=([int]$b.Groups[4].Value-[int]$b.Groups[2].Value)} } } }
  return $r }
function FindTxt { param($xml,[string]$t) return ((Nodes $xml) | Where-Object { $_.text -eq $t -or $_.'content-desc' -eq $t } | Select-Object -First 1) }
function FocET { return (GetFormET (Dump $script:focTag)) | Where-Object { $_.foc -eq 'true' } | Select-Object -First 1 }

# ---- reset app ----
Log "=== reset app ==="
A @("shell","am","force-stop","com.xiufeigo.opencodeshell") | Out-Null
Start-Sleep -Milliseconds 3000
A @("shell","am","start","-n","com.xiufeigo.opencodeshell/.OcActivity") | Out-Null
Start-Sleep -Milliseconds 7000
$fo = Focus
if (-not $fo) { Log "ABORT: app did not start (focus=$fo)"; exit 1 }
Log "FOCUS=$fo"

# ---- nav ----
Log "=== nav to Servers ==="
$x = Dump "U00"
$st = FindTxt $x "Settings"
if (-not $st) { Log "ABORT: no Settings"; exit 1 }
$c = Center $st; Tap $c; Start-Sleep -Milliseconds 2500
$sv=$null
for ($i=1; $i -le 8; $i++) { $x = Dump "U01"; $sv = FindTxt $x "Servers"; if ($sv) { break }; Swipe 950 745 150 745 400 }
if (-not $sv) { Log "ABORT: no Servers tab"; exit 1 }
$c = Center $sv; Log "tap Servers @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2500
$x = Dump "U02"
$add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
if (-not $add) { Log "ABORT: no Add SSH server"; exit 1 }
$c = Center $add; Log "tap Add SSH server @ $($c.x),$($c.y)"; Tap $c; Start-Sleep -Milliseconds 2500
$x = Dump "U03"
if (-not (FindTxt $x "Add server")) { Log "ABORT: form did not open"; exit 1 }
Log "form OPEN"

# ---- 1) TARGET: tap tallest form ET ----
$script:focTag = "U04"
$ets = GetFormET $x
foreach ($e in $ets) { Log "  pre pw=$($e.pw) h=$($e.h) [$($e.b)] c=$($e.cx),$($e.cy)" }
$t = $ets | Sort-Object h -Descending | Select-Object -First 1
Log ">>> tap TARGET @ $($t.cx),$($t.cy) [$($t.b)] h=$($t.h)"
TapAt $t.cx $t.cy; Start-Sleep -Milliseconds 1000
$fo = FocET
if (-not $fo) { Log "ABORT: target not focused"; exit 1 }
Log "  focus OK [$($fo.b)]"
A @("shell","input","text","ssh%s-p%s2222%ssshtest@127.0.0.1") | Out-Null; Start-Sleep -Milliseconds 1300
$script:focTag = "U05"
$rb = GetFormET (Dump "U05")
foreach ($e in $rb) { Log "  rb pw=$($e.pw) [$($e.b)] val='$($e.val)'" }
if (-not ($rb | Where-Object { $_.val -eq 'ssh -p 2222 sshtest@127.0.0.1' })) { Log "ABORT: target readback fail"; exit 1 }
Log "TARGET OK"

# ---- 2) NAME: TAB 1 ----
A @("shell","input","keyevent","61") | Out-Null; Start-Sleep -Milliseconds 1100
$script:focTag = "U06"
$fo = FocET
Log "after TAB1 focus: $(if($fo){'['+$fo.b+'] pw='+$fo.pw+" val='"+$fo.val+"'"}else{'NONE'})"
if (-not $fo -or $fo.pw -eq 'true') { Log "ABORT: TAB1 wrong field"; exit 1 }
A @("shell","input","text","E2ECHK5554d3d-1") | Out-Null; Start-Sleep -Milliseconds 1300
$rb = GetFormET (Dump "U07")
foreach ($e in $rb) { Log "  rb pw=$($e.pw) [$($e.b)] val='$($e.val)'" }
if (-not ($rb | Where-Object { $_.val -eq 'E2ECHK5554d3d-1' })) { Log "ABORT: name readback fail"; exit 1 }
Log "NAME OK"

# ---- 3) PASSWORD: TAB until pw=true ----
$pok=$false
for ($k=1; $k -le 4; $k++) {
  A @("shell","input","keyevent","61") | Out-Null; Start-Sleep -Milliseconds 1000
  $script:focTag = "U08_$k"
  $fo = FocET
  Log "TAB $k focus: $(if($fo){'pw='+$fo.pw+" ["+$fo.b+"]"}else{'NONE'})"
  if ($fo -and $fo.pw -eq 'true') { $pok=$true; break }
}
if (-not $pok) { Log "ABORT: password not reached by TAB"; exit 1 }
A @("shell","input","text","keypass123") | Out-Null; Start-Sleep -Milliseconds 1300
$rb = GetFormET (Dump "U09")
foreach ($e in $rb) { Log "  rb pw=$($e.pw) [$($e.b)] val='$($e.val)'" }
Log "PASSWORD typed"
Shot "U09_filled"

# ---- 4) SUBMIT ----
$x = Dump "U10"
$as = FindTxt $x "Add server"
if (-not $as) { Log "ABORT: Add server not found"; exit 1 }
$c = Center $as; Log ">>> tap Add server @ $($c.x),$($c.y)"; Tap $c
Start-Sleep -Milliseconds 5000
$x = Dump "U11"
$card = (Nodes $x) | Where-Object { $_.text -eq 'Verify host identity' }
Log "=== cardUp=$(if($card){'TRUE'}else{'FALSE'}) ==="
foreach ($z in (Nodes $x)) { $cc=Center $z; if ($cc.t -gt 1000 -and ($z.text -ne '' -or $z.'content-desc' -ne '')) { Log ("  " + $z.text + " | cd=" + $z.'content-desc' + " | clk=" + $z.clickable + " | " + $z.bounds) } }
Shot "U11_card"
