# verified delete: iterate candidate kebabs, trust ONLY the confirm-bar name
function DelVerified { param([string]$name,[switch]$Quick)
  $res = @{ok=$false; gone=$false; confirmB=''; confirmY=''; rowsBefore=''; rowsAfter=''; tried=0}
  if (-not (EnsurePanel)) { Log "  [DV] panel unreachable"; return $res }
  # make sure the row is on screen
  $found=$false; $x=$null
  for ($i=0; $i -lt 8; $i++) {
    $x = Dump "DV_${name}_p$i"
    foreach ($r in (PanelRows $x)) { if ($r.v -eq $name) { $found=$true } }
    if ($found) { break }
    Swipe 500 1800 500 1200 350 }
  if (-not $found) { Log "  [DV] row '$name' never became visible"; return $res }
  $res.rowsBefore = ((PanelRows $x | ForEach-Object { $_.v }) -join '|')
  Log "  [DV] rows on screen before = [$($res.rowsBefore)]"
  # candidate kebabs, nearest-to-row first is not reliable -> just try each
  $cands=@()
  foreach ($z in (Nodes $x)) { if ($z.'content-desc' -eq 'Server actions') { $c=Center $z; $cands += [pscustomobject]@{x=$c.x;y=$c.y;b=$z.bounds} } }
  Log "  [DV] $($cands.Count) kebab candidate(s) on screen"
  foreach ($k in $cands) {
    if ($Quick) { break }
    $res.tried++
    Log "  [DV] try$($res.tried): kebab @ $($k.x),$($k.y) [$($k.b)]"
    TapAt $k.x $k.y; Start-Sleep -Milliseconds 1500
    $x = Dump "DV_${name}_m$($res.tried)"
    $del=$null
    foreach ($z in (Nodes $x)) { if ($z.text -eq 'Delete' -or $z.'content-desc' -eq 'Delete') { $del=$z } }
    if (-not $del) { Log "  [DV] try$($res.tried): no Delete menu item"; continue }
    $dc=Center $del; Log "  [DV] try$($res.tried): menu Delete [$($del.bounds)]"
    TapAt $dc.x $dc.y; Start-Sleep -Milliseconds 1600
    $x = Dump "DV_${name}_c$($res.tried)"
    $t1 = (Nodes $x) | Where-Object { $_.text -eq 'Delete SSH server?' } | Select-Object -First 1
    if (-not $t1) { Log "  [DV] try$($res.tried): no confirm bar"; continue }
    $ty=(Center $t1).y
    $body=$null
    foreach ($z in (Nodes $x)) { if ($z.text -like 'This removes*') { $body=$z.text } }
    $will=''; $m=[regex]::Match([string]$body,'This removes\s*[“"'']([^”"'']+)[”"'']')
    if ($m.Success) { $will=$m.Groups[1].Value }
    Log "  [DV] try$($res.tried): confirm title [$($t1.bounds)] body='$body' -> WILL REMOVE '$will'"
    if ($will -ne $name) { Log "  [DV] mismatch -> dismiss, try next kebab"
      $cc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt $ty) { $cc=$c } } }
      if ($cc) { TapAt $cc.x $cc.y; Start-Sleep -Milliseconds 1500 }
      continue }
    # MATCH -> take the Delete button below the title (right-hand side)
    $cf=$null
    foreach ($z in (Nodes $x)) { if ($z.text -eq 'Delete' -or $z.'content-desc' -eq 'Delete') { $c=Center $z; if ($c.y -gt $ty -and ($cf -eq $null -or $c.y -gt $cf.y)) { $cf=$c } } }
    if (-not $cf) { Log "  [DV] match but no confirm button below title (y=$ty)"; continue }
    $res.confirmB="Delete@$($cf.x),$($cf.y)"; $res.confirmY="$($cf.y)"
    Log "  [DV] CONFIRM MATCH '$will' -> tap Delete @ $($cf.x),$($cf.y)"
    Shot "DV_$name`_confirm"
    TapAt $cf.x $cf.y; Start-Sleep -Milliseconds 2500
    $x = Dump "DV_${name}_after"
    $gone = -not (($x) -match [regex]::Escape($name))
    $after=@()
    for ($i=0; $i -lt 5; $i++) { $after += (PanelRows $x | ForEach-Object { $_.v }); Swipe 500 1800 500 1200 350; $x = Dump "DV_${name}_a$i" }
    $res.rowsAfter = (($after | Sort-Object -Unique) -join '|')
    Log "  [DV] RESULT ok=$true gone=$gone rowsAfter=[$($res.rowsAfter)]"
    $res.ok=$true; $res.gone=$gone
    return $res }
  Log "  [DV] exhausted $($res.tried) kebab(s); could not get a matching confirm for '$name'"
  return $res }
