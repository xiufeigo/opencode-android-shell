$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c7lib.ps1"
. "$W\b_funcs.ps1"
function Prows { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { if ($z.text -match '^E2ECHK5554d3d-\d+$') { $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
      $c=Center $z; $r += [pscustomobject]@{v=$z.text; l=[int]$b.Groups[1].Value; b=$z.bounds; cy=$c.cy; t=[int]$b.Groups[2].Value} } }; return $r }
function TopUp { for ($i=0; $i -lt 6; $i++) { Swipe 540 1150 540 1700 350 } }
function Sweep { param([string]$tag)
  $seen=@{}
  for ($i=0; $i -le 5; $i++) { $x = Dump "$tag`_s$i"; foreach ($p in (Prows $x)) { if ($p.l -ge 190 -and $p.l -le 210) { $seen[$p.v]=$p } }; Swipe 540 1600 540 1100 400 }
  return $seen }
function CloseCard { param([string]$tag)
  for ($k=0; $k -lt 4; $k++) {
    $x = Dump "$tag`_c$k"
    if (CardUp $x) { $cc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $cc=$c } } }
      if ($cc) { Log "  [$tag] card Cancel @ $($cc.x),$($cc.y)"; Tap $cc; Start-Sleep -Milliseconds 2000; continue } }
    if (FindTxt $x "Add server") { $fc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $fc=$c } } }
      if ($fc) { Log "  [$tag] footer Cancel @ $($fc.x),$($fc.y)"; Tap $fc; Start-Sleep -Milliseconds 2000; continue } }
    return $true }
  return $false }
function DeleteRow { param([string]$name,[string]$tag)
  $dots=$null
  for ($i=0; $i -le 6; $i++) {
    $x = Dump "$tag`_d$i"
    $row = (Prows $x) | Where-Object { $_.v -eq $name -and $_.l -ge 190 -and $_.l -le 210 } | Select-Object -First 1
    if ($row) { foreach ($d in ((Nodes $x) | Where-Object { $_.'content-desc' -eq 'Server actions' -and $_.clickable -eq 'true' })) { $dc=Center $d; if ($dc.y -gt $row.t -and $dc.y -lt ($row.t + 300)) { $dots=$d; break } }
      if ($dots) { Log "  [$tag] row[$($row.b)] dots[$($dots.bounds)] sweep $i"; break } }
    Swipe 540 1600 540 1100 400 }
  if (-not $dots) { Log "  [$tag] FAIL dots for $name"; return $false }
  $dc=Center $dots; Tap $dc; Start-Sleep -Milliseconds 1700
  $x = Dump "$tag`_m"
  $del = (Nodes $x) | Where-Object { $_.text -eq 'Delete' } | Select-Object -First 1
  if (-not $del) { Log "  [$tag] FAIL no Delete"; return $false }
  $dlc=Center $del; Log "  [$tag] tap Delete @ $($dlc.x),$($dlc.y)"; Tap $dlc; Start-Sleep -Milliseconds 1700
  $x = Dump "$tag`_c"
  $t=$null; foreach ($z in (Nodes $x)) { if ($z.text -like 'This removes*') { $t=$z } }
  $db = (Nodes $x) | Where-Object { $_.text -eq 'Delete' } | Select-Object -Last 1
  if (-not $db) { Log "  [$tag] FAIL no confirm"; return $false }
  $dbc=Center $db
  Log "  [$tag] CONFIRM bar: '" + $t.text + "'"
  Log "  [$tag] CONFIRM Delete bounds=[$($db.bounds)] y=$($dbc.t)..$($dbc.bt) viewport=2424 inViewport=" + ($dbc.t -ge 0 -and $dbc.bt -le 2424)
  Tap $dbc; Start-Sleep -Milliseconds 2200
  return $true }

Log "########## D3-4: Connecting-state delete ##########"
CloseCard "d4a" | Out-Null
TopUp
$seen = Sweep "D4"
$keys = @($seen.Keys | Sort-Object)
Log "rows=[$($keys -join '|')] count=$($keys.Count)"
TunnelLen "d4_before" | Out-Null
if ($keys.Count -gt 0) {
  $victim = $keys[0]
  Log "victim = $victim"
  $rc=$null
  for ($i=0; $i -le 6; $i++) {
    $x = Dump "D4r$i"
    $row = (Prows $x) | Where-Object { $_.v -eq $victim -and $_.l -ge 190 -and $_.l -le 210 } | Select-Object -First 1
    if ($row) { foreach ($z in (Nodes $x)) { if ($z.text -eq 'Reconnect') { $zc=Center $z; if ($zc.t -gt $row.t -and $zc.t -lt ($row.t + 300)) { $rc=$zc; break } } }
      if ($rc) { Log "  Reconnect @ $($rc.x),$($rc.y) for row[$($row.b)]"; break } }
    Swipe 540 1600 540 1100 400 }
  if ($rc) {
    Log ">>> tap Reconnect @ $($rc.x),$($rc.y)"
    Tap $rc
    for ($k=1; $k -le 3; $k++) {
      Start-Sleep -Milliseconds 700
      $x = Dump "D4s$k"
      $st = (States $x) -join ' / '
      Log "  [connect sample $k] states=[$st] cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'})"
      if ($st -match 'Connecting') { break } }
    $x = Dump "D4c"
    $st = (States $x) -join ' / '
    Shot "D4_connecting"
    if ($st -match 'Connecting') {
      Log "  >>> D3-4 Connecting OBSERVED -> delete immediately"
      DeleteRow $victim "D4x" | Out-Null
      $a2 = Sweep "D4a2"
      $a2k = @($a2.Keys | Sort-Object)
      Log "  ROWS_AFTER=[$($a2k -join '|')] COUNT=$($a2k.Count) GONE=$(if($a2k -contains $victim){'NO'}else{'YES'})"
      TunnelLen "d4_after" | Out-Null
      Shot "D4_after_del"
    } else { Log "  state not Connecting: [$st]" }
  } else { Log "  Reconnect not found" }
}

Log "########## CLEANUP ##########"
CloseCard "cl0" | Out-Null
for ($pass=1; $pass -le 5; $pass++) {
  $s = Sweep "CL$pass"
  $k = @($s.Keys | Sort-Object)
  Log "pass $pass rows=[$($k -join '|')] count=$($k.Count)"
  if ($k.Count -eq 0) { break }
  DeleteRow $k[0] "CLp$pass" | Out-Null
}
TopUp
$fin = Sweep "CLfin"
$fk = @($fin.Keys | Sort-Object)
Log "=== CLEANUP RESULT rows=[$($fk -join '|')] count=$($fk.Count) ==="
TunnelLen "cleanup_final" | Out-Null
$x = Dump "CLz"
Log "--- panel neighbours (must be intact) ---"
foreach ($z in (Nodes $x)) { $c=Center $z; if ($c.t -gt 1000 -and ($z.text -ne '' -or $z.'content-desc' -ne '')) { Log ("  '" + $z.text + "' cd=" + $z.'content-desc' + " [" + $z.bounds + "]") } }
Shot "CL_final"
Log "--- private dir residue ---"
Log "grep: [$(($(A @('shell','run-as','com.xiufeigo.opencodeshell','sh','-c','grep -rl E2ECHK5554d3d . 2>/dev/null')) | Out-String).Trim())]"
Log "--- /sdcard ---"
Log "sdcard: $((($(A @('shell','ls','/sdcard')) | Out-String).Trim()) -replace "`n",' ')"
Log "--- reverse ---"
Log "reverse: $((($(A @('reverse','--list')) | Out-String).Trim()) -replace "`n",' ')"
