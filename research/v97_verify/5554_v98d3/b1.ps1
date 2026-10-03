$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c7lib.ps1"
. "$W\b_funcs.ps1"

Log "########## B1: build entry 3 ##########"
$ok = CreateEntry "E2ECHK5554d3d-3"
if (-not $ok) { Log "B1 create 3 failed"; exit 1 }

# dismiss the card that is now up (entry 3's), then footer Cancel
$x = Dump "B10"
if (CardUp $x) { $cc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $cc=$c } } }
  if ($cc) { Log "tap card Cancel @ $($cc.x),$($cc.y)"; Tap $cc; Start-Sleep -Milliseconds 2200 } }
$x = Dump "B11"
$fc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $fc=$c } } }
if ($fc) { Log "tap footer Cancel @ $($fc.x),$($fc.y)"; Tap $fc; Start-Sleep -Milliseconds 2200 }

Log "########## B1: enumerate rows (scroll sweep) ##########"
$seen=@{}
for ($i=0; $i -le 6; $i++) {
  Log "  IME=$(ImeOff)"
  $x = Dump "B1s$i"
  foreach ($p in (PanelRows $x)) { $seen[$p.v] = $p }
  foreach ($s in (States $x)) { Log "   state: $s" }
  Swipe 540 1600 540 1100 400
}
Log "=== ROWS ENUMERATED (scroll sweep, IME asserted false each step) ==="
$seen.Keys | Sort-Object | ForEach-Object { Log "  ROW $_ [$($seen[$_].b)]" }
Log "ROWCOUNT=$($seen.Count)"
TunnelLen "b1_three_rows" | Out-Null
Shot "B1_three_rows"

# ---- D3-3: delete the 3rd row ----
Log "########## D3-3: delete 3rd row ##########"
$target = ($seen.Keys | Sort-Object)[2]
Log "3rd row = $target"
# find its ⋮ : scroll until the target row is visible, then take the Server actions node at the same y band
$dotsNode=$null
for ($i=0; $i -le 6; $i++) {
  $x = Dump "B2d$i"
  $row = (PanelRows $x) | Where-Object { $_.v -eq $target } | Select-Object -First 1
  if ($row) {
    $cands = (Nodes $x) | Where-Object { $_.'content-desc' -eq 'Server actions' -and $_.clickable -eq 'true' }
    foreach ($d in $cands) { $dc=Center $d; if ($dc.y -gt $row.cy -and $dc.y -lt ($row.cy + 200)) { $dotsNode = $d; break } }
    if ($dotsNode) { Log "  found row + dots at swipe $i"; break }
  }
  Swipe 540 1600 540 1100 400
}
if (-not $dotsNode) { Log "D3-3: could not locate 3rd row dots"; exit 1 }
$dc = Center $dotsNode
Log ">>> tap 3rd row dots @ $($dc.x),$($dc.y) [$($dotsNode.bounds)]"
Tap $dc; Start-Sleep -Milliseconds 1800
$x = Dump "B2menu"
Log "--- menu ---"
foreach ($z in (Nodes $x)) { $c=Center $z; if ($c.t -gt 1000 -and ($z.text -ne '' -or $z.'content-desc' -ne '')) { Log ("  '" + $z.text + "' clk=" + $z.clickable + " [" + $z.bounds + "]") } }
$del = (Nodes $x) | Where-Object { $_.text -eq 'Delete' } | Select-Object -First 1
if (-not $del) { Log "D3-3: no Delete in menu"; exit 1 }
$dlc = Center $del
Log ">>> tap Delete @ $($dlc.x),$($dlc.y) [$($del.bounds)]"
Tap $dlc; Start-Sleep -Milliseconds 1800
$x = Dump "B2conf"
Log "--- confirmation bar ---"
$cb = (Nodes $x) | Where-Object { $_.text -like 'Delete SSH server*' -or $_.text -eq 'Delete' -or $_.text -eq 'Cancel' -or $_.text -like 'This removes*' }
foreach ($z in $cb) { $c=Center $z; Log ("  CONF '" + $z.text + "' clk=" + $z.clickable + " [" + $z.bounds + "] c=$($c.x),$($c.y)") }
$delBtn = $null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Delete' -and $z.clickable -eq 'true') { $delBtn = $z } }
if (-not $delBtn) { Log "D3-3: no clickable Delete confirm button"; Shot "B2conf_fail"; exit 1 }
$dbc = Center $delBtn
Log "CONFIRM Delete y-range = $($dbc.y) .. bounds [$($delBtn.bounds)] viewportHeight=2424 inViewport=$($dbc.t -ge 0 -and $dbc.bt -le 2424)"
Shot "B2conf_bar"
Log ">>> tap CONFIRM Delete @ $($dbc.x),$($dbc.y)"
Tap $dbc; Start-Sleep -Milliseconds 2200
Log "--- rows after delete ---"
$after=@{}
for ($i=0; $i -le 6; $i++) { $x2 = Dump "B2a$i"; foreach ($p in (PanelRows $x2)) { $after[$p.v]=$p }; Swipe 540 1600 540 1100 400 }
$after.Keys | Sort-Object | ForEach-Object { Log "  ROW_AFTER $_" }
Log "ROWCOUNT_AFTER=$($after.Count)"
Log "TARGET_GONE=$(if($after.ContainsKey($target)){'NO-STILL-THERE'}else{'YES'})"
TunnelLen "b2_after_del3" | Out-Null
Shot "B2_after_del3"
