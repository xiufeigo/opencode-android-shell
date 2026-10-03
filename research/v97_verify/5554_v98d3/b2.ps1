$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c7lib.ps1"
. "$W\b_funcs.ps1"

# STRICT panel-row anchor: panel row labels sit at left=201 (dialog fields are 138/106)
function Prows { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) {
    if ($z.text -match '^E2ECHK5554d3d-\d+$') {
      $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
      $c=Center $z
      $r += [pscustomobject]@{v=$z.text; l=[int]$b.Groups[1].Value; b=$z.bounds; cy=$c.cy; t=[int]$b.Groups[2].Value} } }
  return $r }
function SweepRows { param([string]$tag)
  $seen=@{}
  for ($i=0; $i -le 5; $i++) {
    $x = Dump "$tag`_s$i"
    $ime = ImeOff
    foreach ($p in (Prows $x)) { if ($p.l -ge 190 -and $p.l -le 210) { $seen[$p.v]=$p } }
    Log "  [$tag s$i] IME=$ime panelRows=[$((Prows $x | Where-Object { $_.l -ge 190 -and $_.l -le 210 } | ForEach-Object { $_.v }) -join '|')]"
    Swipe 540 1600 540 1100 400
  }
  return $seen }

Log "########## ensure panel is clean ##########"
EnsurePanel | Out-Null
$x = Dump "D00"
Log "dialog still open=$(if(FindTxt $x 'Add server'){'YES'}else{'NO'})  cardUp=$(if(CardUp $x){'TRUE'}else{'FALSE'})"

Log "########## B1: enumerate panel rows (strict anchor, IME asserted) ##########"
$seen = SweepRows "B1"
Log "=== PANEL ROWS ENUMERATED ==="
$keys = @($seen.Keys | Sort-Object)
$keys | ForEach-Object { Log "  ROW $_ [$($seen[$_].b)] cy=$($seen[$_].cy)" }
Log "PANEL_ROWCOUNT=$($keys.Count)"
TunnelLen "b1_enumerated" | Out-Null
Shot "B1_panel_rows"

if ($keys.Count -lt 3) { Log "D3-3 ABORT: only $($keys.Count) panel rows" ; exit 1 }
$target = $keys[2]
Log "########## D3-3: 3rd row = $target ##########"

# locate that row's own dots
$dots=$null
for ($i=0; $i -le 5; $i++) {
  $x = Dump "D2d$i"
  $row = (Prows $x) | Where-Object { $_.v -eq $target -and $_.l -ge 190 -and $_.l -le 210 } | Select-Object -First 1
  if ($row) {
    foreach ($d in ((Nodes $x) | Where-Object { $_.'content-desc' -eq 'Server actions' -and $_.clickable -eq 'true' })) {
      $dc = Center $d
      if ($dc.y -gt $row.t -and $dc.y -lt ($row.t + 300)) { $dots=$d; break } }
    if ($dots) { Log "  row[$($row.b)] + dots[$($dots.bounds)] at sweep $i"; break } }
  Swipe 540 1600 540 1100 400
}
if (-not $dots) { Log "D3-3: dots not found"; exit 1 }
$dc = Center $dots
Log ">>> tap dots for 3rd row @ $($dc.x),$($dc.y)"
Tap $dc; Start-Sleep -Milliseconds 1800
$x = Dump "D2menu"
Log "--- menu items ---"
foreach ($z in (Nodes $x)) { $c=Center $z; if ($c.t -gt 1000 -and ($z.text -ne '' -or $z.'content-desc' -ne '')) { Log ("  '" + $z.text + "' clk=" + $z.clickable + " [" + $z.bounds + "]") } }
$del = (Nodes $x) | Where-Object { $_.text -eq 'Delete' } | Select-Object -First 1
if (-not $del) { Log "no Delete"; exit 1 }
$dlc = Center $del; Log ">>> tap Delete @ $($dlc.x),$($dlc.y) [$($del.bounds)]"; Tap $dlc; Start-Sleep -Milliseconds 1800
$x = Dump "D2conf"
Log "--- confirmation bar (viewport height 2424) ---"
foreach ($z in (Nodes $x)) { $c=Center $z; if (($z.text -like 'Delete SSH server*' -or $z.text -eq 'Delete' -or $z.text -eq 'Cancel' -or $z.text -like 'This removes*') -and $c.t -gt 1200) { Log ("  CONF '" + $z.text + "' clk=" + $z.clickable + " [" + $z.bounds + "] c=$($c.x),$($c.y) inViewport=" + ($c.t -ge 0 -and $c.bt -le 2424)) } }
$db = (Nodes $x) | Where-Object { $_.text -eq 'Delete' -and $_.clickable -eq 'true' } | Select-Object -First 1
if (-not $db) { Log "no clickable confirm Delete"; Shot "D2conf_fail"; exit 1 }
$dbc = Center $db
Log "CONFIRM Delete bounds=[$($db.bounds)] y=$($dbc.t)..$($dbc.bt) inViewport2424=$($dbc.t -ge 0 -and $dbc.bt -le 2424)"
Shot "D2_confirm_bar"
Log ">>> tap CONFIRM @ $($dbc.x),$($dbc.y)"
Tap $dbc; Start-Sleep -Milliseconds 2200
$after = SweepRows "D2a"
$ak = @($after.Keys | Sort-Object)
Log "=== ROWS AFTER DELETE ==="
$ak | ForEach-Object { Log "  ROW_AFTER $_" }
Log "PANEL_ROWCOUNT_AFTER=$($ak.Count)"
Log "TARGET_GONE=$(if($ak -contains $target){'NO'}else{'YES'})"
TunnelLen "b2_after_del3" | Out-Null
Shot "D2_after_delete"
