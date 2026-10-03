# b8_funcs.ps1 — round-8 helpers (fixes per REPORT_COVERAGE7 §4.6)
$ErrorActionPreference='Continue'

function GetFormET { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { if ($z.class -like '*EditText') { $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
    if ([int]$b.Groups[1].Value -eq 138) { $cc=Center $z; $r += [pscustomobject]@{b=$z.bounds; cx=$cc.x; cy=$cc.y; pw=$z.password; val=$z.text; foc=$z.focused; h=([int]$b.Groups[4].Value-[int]$b.Groups[2].Value)} } } }
  return $r }
function FindTxt { param($xml,[string]$t) return ((Nodes $xml) | Where-Object { $_.text -eq $t -or $_.'content-desc' -eq $t } | Select-Object -First 1) }
function CardUp { param($xml) return [bool]((Nodes $xml) | Where-Object { $_.text -eq 'Verify host identity' }) }
function AuthCardUp { param($xml) return [bool]((Nodes $xml) | Where-Object { $_.text -eq 'SSH authentication' }) }

# ROW ANCHOR x=201 (left edge of panel row label). Avoids counting dialog title / form field.
function PanelRows { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
    $l=[int]$b.Groups[1].Value
    if ($v -match '^E2ECHK5554d3e-\d+$' -and $l -ge 190 -and $l -le 210) { $c=Center $z; $r += [pscustomobject]@{v=$v; b=$z.bounds; cy=$c.y} } }
  return $r }
# state text of a row: node starting at x=201 below the label
function RowStates { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    $b=[regex]::Match($z.bounds,'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
    $l=[int]$b.Groups[1].Value
    if ($v -match '^(Connecting|Connected|Disconnected|Authentication required)' -and $l -ge 190 -and $l -le 210) { $r += $v } }
  return $r }
function AllStates { param($xml)
  $r=@(); foreach ($z in (Nodes $xml)) { $v = if ($z.text) { $z.text } else { $z.'content-desc' }
    if ($v -match 'Connecting|Connected|Disconnected|Authentication required') { $r += $v } }; return $r }
function TunnelLen { param([string]$tag)
  $o = A @("shell","run-as","com.xiufeigo.opencodeshell","cat","shared_prefs/oc_server_config.xml")
  $t=($o|Out-String); Set-Content "$W\_prefs8_$tag.txt" -Value $t -Encoding UTF8
  $m=[regex]::Match($t,'<string name="AXhs57KqmMxUXQA/h1gxbGu9ZbW7vQQstN5gTAw="[^>]*>([^<]*)</string>')
  $len = if($m.Success){$m.Groups[1].Value.Length}else{-1}
  Log "  TUNNELKEY[$tag] len=$len  file=$((Get-Item "$W\_prefs8_$tag.txt").Length)B"
  return $len }

# scroll panel to TOP (fix 6: Add SSH server is a top-pinned item, leaves tree when scrolled)
function ScrollTop { param([int]$n=4)
  for ($i=0;$i -lt $n;$i++){ Swipe 500 1000 500 1900 350 } }

# EnsurePanel: dismiss card -> dismiss form -> scroll to top -> find Add SSH server
function EnsurePanel {
  for ($k=0; $k -lt 6; $k++) {
    $x = Dump "EP8_$k"
    # stale delete-confirm bar: dismiss via its own Cancel (below the title)
    $cb = (Nodes $x) | Where-Object { $_.text -eq 'Delete SSH server?' } | Select-Object -First 1
    if ($cb) { $ty=(Center $cb).y; $cc=$null
      foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt $ty) { $cc=$c } } }
      if ($cc) { Log "  [Ensure] confirmbar->Cancel @ $($cc.x),$($cc.y)"; Tap $cc; Start-Sleep -Milliseconds 1800; continue }
      Log "  [Ensure] confirmbar up but no Cancel found (title y=$ty)" }
    if (CardUp $x) { $cc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $cc=$c } } }
      if ($cc) { Log "  [Ensure] card->Cancel @ $($cc.x),$($cc.y)"; Tap $cc; Start-Sleep -Milliseconds 2000; continue } }
    if (AuthCardUp $x) { $cc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $cc=$c } } }
      if ($cc) { Log "  [Ensure] authcard->Cancel @ $($cc.x),$($cc.y)"; Tap $cc; Start-Sleep -Milliseconds 2000; continue } }
    $fc=$null; foreach ($z in (Nodes $x)) { if ($z.text -eq 'Cancel' -or $z.'content-desc' -eq 'Cancel') { $c=Center $z; if ($c.y -gt 1700) { $fc=$c } } }
    if ($fc) { Log "  [Ensure] form->Cancel @ $($fc.x),$($fc.y)"; Tap $fc; Start-Sleep -Milliseconds 2000; continue } }
    $add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
    if ($add) { Log "  [Ensure] panel clean"; return $true }
    Log "  [Ensure] round $k no panel (focus=$(Focus)) -> scroll top + retry"
    ScrollTop 3
    $x = Dump "EP8t_$k"
    $add = (Nodes $x) | Where-Object { $_.text -eq 'Add SSH server' -or $_.'content-desc' -eq 'Add SSH server' } | Select-Object -First 1
    if ($add) { Log "  [Ensure] panel clean (after scrollTop)"; return $true }
    $st = FindTxt $x "Settings"
    if ($st) { $c=Center $st; Tap $c; Start-Sleep -Milliseconds 2200; $sv=$null
      for ($i=1;$i -le 8;$i++){ $x=Dump "EP8s"; $sv=FindTxt $x "Servers"; if($sv){break}; Swipe 950 745 150 745 400 }
      if($sv){ $c=Center $sv; Tap $c; Start-Sleep -Milliseconds 2000 } }
  return $false }

# Enumerate panel rows by scrolling (union of seen names)
function EnumRows { param([string]$tag)
  $seen=@{}; $detail=@{}
  if (-not (EnsurePanel)) { Log "  [Enum] panel unreachable"; return @() }
  ScrollTop 4
  for ($i=0; $i -lt 10; $i++) {
    $x = Dump "EN_${tag}_$i"
    foreach ($r in (PanelRows $x)) { if (-not $seen.ContainsKey($r.v)) { $seen[$r.v]=$true; $detail[$r.v]=$r.b } }
    foreach ($s in (RowStates $x)) { if (-not $seen.ContainsKey("ST:$s")) { $seen["ST:$s"]=$true } }
    $more = $false
    foreach ($z in (Nodes $x)) { if ($z.scrollable -eq 'true' -and $z.bounds -notmatch '') { $more=$true } }
    if (-not $more) { break }
    Swipe 500 1800 500 1200 350
    $x2 = Dump "EN_${tag}_c$i"
    $before = ($seen.Keys | Sort-Object) -join '|'
    $new=$false; foreach ($r in (PanelRows $x2)) { if (-not $seen.ContainsKey($r.v)) { $new=$true } }
    if (-not $new) { break }
  }
  $names = ($seen.Keys | Where-Object { $_ -notlike 'ST:*' } | Sort-Object)
  foreach ($n in $names) { Log "  ROW $n   $($detail[$n])" }
  Log "PANEL_ROWCOUNT=$($names.Count)"
  return $names }

# delete a row by name; returns @{ok; rowsBefore; rowsAfter; gone; confirmB; confirmY}
function DeleteRow { param([string]$name)
  $res = @{ok=$false; gone=$false; confirmB=''; confirmY=''}
  if (-not (EnsurePanel)) { Log "  [Del] panel unreachable"; return $res }
  $x = Dump "DL_$($name)_top"
  $tgt=$null
  foreach ($r in (PanelRows $x)) { if ($r.v -eq $name) { $tgt=$r } }
  if (-not $tgt) {
    for ($i=0; $i -lt 8; $i++) {
      Swipe 500 1800 500 1200 350
      $x = Dump "DL_$($name)_s$i"
      foreach ($r in (PanelRows $x)) { if ($r.v -eq $name) { $tgt=$r; break } }
      if ($tgt) { break } } }
  if (-not $tgt) { Log "  [Del] row '$name' NOT VISIBLE"; return $res }
  Log "  [Del] target row $name [$($tgt.b)]"
  $rowsBefore = (PanelRows $x | ForEach-Object { $_.v }) -join '|'
  Log "  [Del] ROWS_BEFORE=[$rowsBefore]"
  # kebab is on the SAME horizontal band as its row label (fix: was tgt.cy+100 -> picked a neighbour row)
  $cands=@()
  foreach ($z in (Nodes $x)) { if ($z.'content-desc' -eq 'Server actions') { $c=Center $z; $cands += [pscustomobject]@{x=$c.x;y=$c.y;b=$z.bounds;clk=$z.clickable} } }
  if ($cands.Count -eq 0) { Log "  [Del] no Server actions node"; return $res }
  $best = $cands | Sort-Object { [math]::Abs($_.y - $tgt.cy) } | Select-Object -First 1
  Log "  [Del] label cy=$($tgt.cy); kebab @ $($best.x),$($best.y) [$($best.b)] clk=$($best.clk) dY=$([math]::Abs($best.y-$tgt.cy))"
  TapAt $best.x $best.y; Start-Sleep -Milliseconds 1600
  $x = Dump "DL_$($name)_menu"
  $del=$null
  foreach ($z in (Nodes $x)) { if ($z.text -eq 'Delete' -or $z.'content-desc' -eq 'Delete') { $del=$z } }
  if (-not $del) { Log "  [Del] no Delete menu item"; return $res }
  $dc = Center $del; Log "  [Del] menu Delete [$($del.bounds)] -> tap @ $($dc.x),$($dc.y)"
  TapAt $dc.x $dc.y; Start-Sleep -Milliseconds 1800
  $x = Dump "DL_$($name)_confirm"
  $t1 = FindTxt $x "Delete SSH server?"
  if ($t1) { Log "  [Del] confirm title 'Delete SSH server?' [$($t1.bounds)]" }
  else { Log "  [Del] NO confirm bar"; return $res }
  # GUARD: the confirm body names the row that will actually be removed
  $body=$null
  foreach ($z in (Nodes $x)) { if ($z.text -like 'This removes*') { $body=$z.text; Log "  [Del] confirm body '$body' [$($z.bounds)]" } }
  if ($body) {
    $m=[regex]::Match($body,'This removes\s*[“"'']([^”"'']+)[”"'']')
    if ($m.Success) {
      $willDelete=$m.Groups[1].Value
      Log "  [Del] GUARD: confirm will remove '$willDelete' ; intended '$name'"
      if ($willDelete -ne $name) {
        Log "  [Del] *** ABORT: name mismatch, NOT confirming (would delete the wrong row) ***"
        return $res } } }
  # confirm button: the Delete node BELOW the title, right-hand side
  $cf=$null; $ty = if($t1){ (Center $t1).y } else { 0 }
  foreach ($z in (Nodes $x)) { if ($z.text -eq 'Delete' -or $z.'content-desc' -eq 'Delete') { $c=Center $z; if ($c.y -gt $ty -and ($cf -eq $null -or $c.y -gt $cf.y)) { $cf=$c } } }
  if (-not $cf) { Log "  [Del] no confirm Delete button below title (title y=$ty)"; return $res }
  $res.confirmB = "Delete@$($cf.x),$($cf.y)"; $res.confirmY = "$($cf.y)"
  Log "  [Del] CONFIRM Delete @ $($cf.x),$($cf.y)  (below title y=$ty; viewport 2424)"
  TapAt $cf.x $cf.y; Start-Sleep -Milliseconds 2500
  $x = Dump "DL_$($name)_after"
  $gone = -not (($x) -match [regex]::Escape($name))
  $rowsAfter = @()
  for ($i=0; $i -lt 6; $i++) {
    $rowsAfter = $rowsAfter + (PanelRows $x | ForEach-Object { $_.v })
    Swipe 500 1800 500 1200 350
    $x = Dump "DL_$($name)_a$i" }
  $rowsAfter = ($rowsAfter | Sort-Object -Unique) -join '|'
  Log "  [Del] ROWS_AFTER=[$rowsAfter]  TARGET_GONE=$gone"
  $res.ok=$true; $res.gone=$gone; $res.rowsBefore=$rowsBefore; $res.rowsAfter=$rowsAfter
  return $res }

function SetET { param([string]$text)
  A @("shell","input","keyevent","123") | Out-Null
  $dels = (1..70 | ForEach-Object { "67" }) -join " "
  A @("shell","input","keyevent",$dels) | Out-Null
  Start-Sleep -Milliseconds 400
  A @("shell","input","text",$text.Replace(' ','%s')) | Out-Null
  Start-Sleep -Milliseconds 1100 }

