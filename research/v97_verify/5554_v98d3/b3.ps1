$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c7lib.ps1"
. "$W\b_funcs.ps1"
function Prows { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { if ($z.text -match '^E2ECHK5554d3d-\d+$') { $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
      $c=Center $z; $r += [pscustomobject]@{v=$z.text; l=[int]$b.Groups[1].Value; b=$z.bounds; cy=$c.cy; t=[int]$b.Groups[2].Value} } }; return $r }
function Sweep { param([string]$tag)
  $seen=@{}
  for ($i=0; $i -le 5; $i++) { $x = Dump "$tag`_s$i"; foreach ($p in (Prows $x)) { if ($p.l -ge 190 -and $p.l -le 210) { $seen[$p.v]=$p } }; Swipe 540 1600 540 1100 400 }
  return $seen }
function CloseCard { param([string]$tag)
  for ($k=0; $k -lt 3; $k++) {
    $x = Dump "$tag`_c$k"
    if (CardUp $x) { $cc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $cc=$c } } }
      if ($cc) { Log "  [$tag] card Cancel @ $($cc.x),$($cc.y)"; Tap $cc; Start-Sleep -Milliseconds 2000; continue } }
    if (FindTxt $x "Add server") { $fc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $fc=$c } } }
      if ($fc) { Log "  [$tag] footer Cancel @ $($fc.x),$($fc.y)"; Tap $fc; Start-Sleep -Milliseconds 2000; continue } }
    return $true }
  return $false }
function DeleteRow { param([string]$name)
  Log "  --- deleting $name ---"
  $dots=$null
  for ($i=0; $i -le 5; $i++) {
    $x = Dump "DR$i"
    $row = (Prows $x) | Where-Object { $_.v -eq $name -and $_.l -ge 190 -and $_.l -le 210 } | Select-Object -First 1
    if ($row) { foreach ($d in ((Nodes $x) | Where-Object { $_.'content-desc' -eq 'Server actions' -and $_.clickable -eq 'true' })) { $dc=Center $d; if ($dc.y -gt $row.t -and $dc.y -lt ($row.t + 300)) { $dots=$d; break } }
      if ($dots) { Log "    row[$($row.b)] dots[$($dots.bounds)] sweep $i"; break } }
    Swipe 540 1600 540 1100 400 }
  if (-not $dots) { Log "    FAIL: dots for $name not found"; return $false }
  $dc=Center $dots; Tap $dc; Start-Sleep -Milliseconds 1700
  $x = Dump "DRm"
  $del = (Nodes $x) | Where-Object { $_.text -eq 'Delete' } | Select-Object -First 1
  if (-not $del) { Log "    FAIL: no Delete"; return $false }
  $dlc=Center $del; Log "    tap Delete @ $($dlc.x),$($dlc.y) [$($del.bounds)]"; Tap $dlc; Start-Sleep -Milliseconds 1700
  $x = Dump "DRc"
  $t=$null; foreach ($z in (Nodes $x)) { if ($z.text -like 'This removes*') { $t=$z } }
  $db=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Delete' -and $z.bounds -ne $del.bounds) { $db=$z } }
  if (-not $db) { $db = (Nodes $x) | Where-Object { $_.text -eq 'Delete' } | Select-Object -Last 1 }
  if (-not $db) { Log "    FAIL: no confirm Delete"; return $false }
  $dbc=Center $db
  Log "    CONFIRM '" + $t.text + "'"
  Log "    CONFIRM Delete bounds=[$($db.bounds)] y=$($dbc.t)..$($dbc.bt) viewport=2424 inViewport=" + ($dbc.t -ge 0 -and $dbc.bt -le 2424)
  Tap $dbc; Start-Sleep -Milliseconds 2200
  return $true }

# ============ build entries 4 and 5 ============
Log "########## build entry 4 ##########"
CloseCard "n4" | Out-Null
CreateEntry "E2ECHK5554d3d-4" | Out-Null
Log "########## build entry 5 ##########"
CloseCard "n5" | Out-Null
CreateEntry "E2ECHK5554d3d-5" | Out-Null
CloseCard "n5b" | Out-Null
$seen = Sweep "F1"
$keys = @($seen.Keys | Sort-Object)
Log "=== PANEL ROWS (expect 4) ==="
$keys | ForEach-Object { Log "  ROW $_ [$($seen[$_].b)]" }
Log "PANEL_ROWCOUNT=$($keys.Count)"
TunnelLen "f1_four_rows" | Out-Null
Shot "F1_four_rows"

if ($keys.Count -ge 4) {
  Log "########## D3-3: 4th row = $($keys[3]) ##########"
  DeleteRow $keys[3] | Out-Null
  $after = Sweep "F2"
  $ak = @($after.Keys | Sort-Object)
  Log "=== ROWS AFTER 4th-row delete ==="
  $ak | ForEach-Object { Log "  ROW_AFTER $_" }
  Log "COUNT_AFTER=$($ak.Count)  TARGET_GONE=$(if($ak -contains $keys[3]){'NO'}else{'YES'})"
  TunnelLen "f2_after_del4" | Out-Null
  Shot "F2_after_del4"
} else { Log "D3-3 4th row: SKIPPED (only $($keys.Count) rows)" }

Log "########## D3-4: Connecting state delete ##########"
$seen2 = Sweep "F3"
$k2 = @($seen2.Keys | Sort-Object)
Log "rows now=[$($k2 -join '|')]"
if ($k2.Count -gt 0) {
  $victim = $k2[0]
  CloseCard "c1" | Out-Null
  # tap Reconnect on the victim row -> Connecting
  $rc=$null
  for ($i=0; $i -le 5; $i++) {
    $x = Dump "F3r$i"
    $row = (Prows $x) | Where-Object { $_.v -eq $victim -and $_.l -ge 190 -and $_.l -le 210 } | Select-Object -First 1
    if ($row) { foreach ($z in (Nodes $x)) { if ($z.text -eq 'Reconnect') { $zc=Center $z; if ($zc.t -gt $row.t -and $zc.t -lt ($row.t + 300)) { $rc=$zc; break } } }
      if ($rc) { break } }
    Swipe 540 1600 540 1100 400 }
  if ($rc) {
    Log ">>> tap Reconnect on $victim @ $($rc.x),$($rc.y)"
    Tap $rc
    Start-Sleep -Milliseconds 1200
    $x = Dump "F3conn"
    $st = (States $x) -join ' / '
    Log "  state after Reconnect: [$st]  cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'})"
    Shot "F3_connecting_state"
    if ($st -match 'Connecting') {
      Log "  D3-4 Connecting OBSERVED -> deleting immediately"
      DeleteRow $victim | Out-Null
      $a2 = Sweep "F4"
      $a2k = @($a2.Keys | Sort-Object)
      Log "  ROWS_AFTER=[$($a2k -join '|')] COUNT=$($a2k.Count) GONE=$(if($a2k -contains $victim){'NO'}else{'YES'})"
      TunnelLen "f4_after_del_connecting" | Out-Null
      Shot "F4_after_del_connecting"
    } else { Log "  state was not Connecting; skipped" }
  } else { Log "  Reconnect not found for $victim" }
}
