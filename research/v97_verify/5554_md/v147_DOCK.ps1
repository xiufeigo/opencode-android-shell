# v147: raw lines from archived frames + docked-panel form (criteria 1/3) + sdcard residue
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
Add-Type -AssemblyName System.Drawing

"### R1 raw lines from archived frames (verbatim)"
foreach ($f in @('C0_768.xml','C1_600_ONEROW.xml')) {
  $p = "$script:ART\$f"
  if (-not (Test-Path $p)) { "  $f : MISSING"; continue }
  $raw = Get-Content $p -Encoding UTF8
  $line = ($raw -join ' ')
  foreach ($lbl in @('Open terminal','Send')) {
    $m = [regex]::Match($line, '<node index="(\d+)"[^>]*content-desc="' + [regex]::Escape($lbl) + '"[^>]*bounds="(\[[^"]+\])"[^>]*/?>')
    if (-not $m.Success) {
      $m2 = [regex]::Match($line, '<node index="(\d+)"[^>]*bounds="(\[[^"]+\])"[^>]*content-desc="' + [regex]::Escape($lbl) + '"[^>]*/?>')
    }
    if ($m.Success) {
      $seg = $m.Value
      $clk = [regex]::Match($seg,'clickable="(\w+)"').Groups[1].Value
      $ena = [regex]::Match($seg,'enabled="(\w+)"').Groups[1].Value
      $idx = $m.Groups[1].Value
      "  $f  $lbl : index=$idx bounds=$($m.Groups[2].Value) clickable=$clk enabled=$ena"
    } else { "  $f  $lbl : regex miss" }
  }
}

"### R2 dock the terminal panel (tap the chip, no BACK / no ESC)"
adbx shell wm density 160 | Out-Null
adbx shell wm size 1366x768 | Out-Null
Start-Sleep -Seconds 3
$ns = Nodes (Dump 'DOCK_0_before')
$chip = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
"  chip before dock = [$($chip.x1),$($chip.y1)][$($chip.x2),$($chip.y2)]"
Tap $chip
Start-Sleep -Seconds 5
$ime = AssertIme 'after dock tap' | Select-Object -Last 1
"  mInputShown=$ime  appPid=$(AppPid)  focus=$((Focus) -join '')"
Shot 'DOCK_1_after' | Out-Null
$ns2 = Nodes (Dump 'DOCK_1_after')
"  nodes=$($ns2.Count)"

"  -- clickable nodes after docking --"
$ns2 | Where-Object { $_.clickable -eq 'true' } | Select-Object -First 30 | ForEach-Object {
  $lbl = if ($_.text) { $_.text } elseif ($_.desc) { $_.desc } else { '' }
  if ($lbl.Length -gt 40) { $lbl = $lbl.Substring(0,40) }
  "    [{0},{1}][{2},{3}] {4}x{5} {6} lbl='{7}'" -f $_.x1,$_.y1,$_.x2,$_.y2,$_.w,$_.h,$_.cls,$lbl
}

$ot2 = $ns2 | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$sd2 = $ns2 | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
"  chip after dock = $(if($ot2){"[$($ot2.x1),$($ot2.y1)][$($ot2.x2),$($ot2.y2)] $($ot2.w)x$($ot2.h) clickable=$($ot2.clickable)"}else{'ABSENT'})"
"  send after dock = $(if($sd2){"[$($sd2.x1),$($sd2.y1)][$($sd2.x2),$($sd2.y2)] $($sd2.w)x$($sd2.h) clickable=$($sd2.clickable)"}else{'ABSENT'})"

# pane width probe: the viewport pane that hosts the timeline
"  -- wide View/host nodes (candidate panes), to read the docked pane width --"
$ns2 | Where-Object { $_.h -ge 40 -and $_.w -ge 200 -and $_.cls -match 'View|Layout|Frame' } |
  Sort-Object -Property w | Select-Object -First 12 | ForEach-Object {
  "    {0,-18} [{1},{2}][{3},{4}] {5}x{6}" -f $_.cls,$_.x1,$_.y1,$_.x2,$_.y2,$_.w,$_.h }

if ($ot2 -and $sd2) {
  $ix1=[Math]::Max($ot2.x1,$sd2.x1); $iy1=[Math]::Max($ot2.y1,$sd2.y1)
  $ix2=[Math]::Min($ot2.x2,$sd2.x2); $iy2=[Math]::Min($ot2.y2,$sd2.y2)
  $ow=[Math]::Max(0,$ix2-$ix1); $oh=[Math]::Max(0,$iy2-$iy1)
  "  DOCKED C1 overlapArea = $($ow*$oh)  (intersection ${ow}x${oh})"
  "  DOCKED chip.width = $($ot2.w)  (>=48 means not clipped by the screen edge)"
  $bmp=[System.Drawing.Bitmap]::FromFile((Shot 'DOCK_1_scan'))
  $cov=0;$hist=@{}
  for($y=$ot2.y1;$y -lt $ot2.y2;$y++){for($x=$ot2.x1;$x -lt $ot2.x2;$x++){
    if($x -ge $sd2.x1 -and $x -lt $sd2.x2 -and $y -ge $sd2.y1 -and $y -lt $sd2.y2){$cov++;continue}
    $c=$bmp.GetPixel($x,$y);$k="$($c.R),$($c.G),$($c.B)"
    if($hist.ContainsKey($k)){$hist[$k]++}else{$hist[$k]=1}}}
  $bmp.Dispose()
  "  DOCKED chip area=$($ot2.w*$ot2.h) coveredBySend=$cov  notCovered=$($ot2.w*$ot2.h-$cov)"
  "  DOCKED chip-minus-Send histogram (top 5):"
  $hist.GetEnumerator()|Sort-Object Value -Descending|Select-Object -First 5|ForEach-Object{"    $($_.Key) x$($_.Value)"}
  "  DOCKED C3 chipVisiblePixelCount(geometric) = $($ot2.w*$ot2.h-$cov)"
}

"### R3 restore: tap the chip again to undock (no BACK / no ESC)"
if ($ot2) { Tap $ot2; Start-Sleep -Seconds 4 }
$ns3 = Nodes (Dump 'DOCK_2_undocked')
$ot3 = $ns3 | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$sd3 = $ns3 | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
"  after undock: chip=$(if($ot3){"[$($ot3.x1),$($ot3.y1)][$($ot3.x2),$($ot3.y2)]"}else{'ABSENT'})"
"  after undock: send=$(if($sd3){"[$($sd3.x1),$($sd3.y1)][$($sd3.x2),$($sd3.y2)]"}else{'ABSENT'})"

"### R4 /sdcard residue"
adbx shell "ls -la /sdcard/ | grep -iE 'e2e|e2echk|__'" 2>&1 | Out-String
"DOCK done"
