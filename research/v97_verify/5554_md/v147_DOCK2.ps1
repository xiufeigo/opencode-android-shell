# v147: docked form, IME restored before reading; + sdcard residue
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
Add-Type -AssemblyName System.Drawing

"### D0 pre-state at 1366x768"
adbx shell wm density 160 | Out-Null
adbx shell wm size 1366x768 | Out-Null
Start-Sleep -Seconds 3
$ns = Nodes (Dump 'DK_0_before')
$chip = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
"  chip before = [$($chip.x1),$($chip.y1)][$($chip.x2),$($chip.y2)] $($chip.w)x$($chip.h)"
"  ime before = $(ImeState)"

"### D1 tap chip to dock"
Tap $chip
Start-Sleep -Seconds 5
"  ime after tap = $(ImeState)  appPid=$(AppPid)"
Shot 'DK_1_docked' | Out-Null
$ns1 = Nodes (Dump 'DK_1_docked')

"### D2 restore IME by tapping the composer EditText"
$et = $ns1 | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
if ($et) { "  EditText = [$($et.x1),$($et.y1)][$($et.x2),$($et.y2)]"; Tap $et; Start-Sleep -Seconds 4 }
"  ime after composer tap = $(ImeState)"
$ime = AssertIme 'docked readings' | Select-Object -Last 1
Shot 'DK_2_docked_ime' | Out-Null
$ns2 = Nodes (Dump 'DK_2_docked_ime')
"  mInputShown=$ime  nodes=$($ns2.Count)  focus=$((Focus) -join '')"

"  -- clickable nodes in docked form --"
$ns2 | Where-Object { $_.clickable -eq 'true' } | Select-Object -First 26 | ForEach-Object {
  $lbl = if ($_.text) { $_.text } elseif ($_.desc) { $_.desc } else { '' }
  if ($lbl.Length -gt 34) { $lbl = $lbl.Substring(0,34) }
  "    [{0},{1}][{2},{3}] {4}x{5} {6} lbl='{7}'" -f $_.x1,$_.y1,$_.x2,$_.y2,$_.w,$_.h,$_.cls,$lbl
}

$ot = $ns2 | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$sd = $ns2 | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
"  chip = $(if($ot){"[$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)] $($ot.w)x$($ot.h) clickable=$($ot.clickable)"}else{'ABSENT'})"
"  send = $(if($sd){"[$($sd.x1),$($sd.y1)][$($sd.x2),$($sd.y2)] $($sd.w)x$($sd.h) clickable=$($sd.clickable)"}else{'ABSENT'})"

"  -- pane candidates (wide hosts) --"
$ns2 | Where-Object { $_.h -ge 40 -and $_.w -ge 150 -and $_.cls -match 'View|Frame' } |
  Sort-Object -Property w | Select-Object -First 10 | ForEach-Object {
  "    {0,-16} [{1},{2}][{3},{4}] {5}x{6}" -f $_.cls,$_.x1,$_.y1,$_.x2,$_.y2,$_.w,$_.h }

if ($ot -and $sd) {
  $ix1=[Math]::Max($ot.x1,$sd.x1); $iy1=[Math]::Max($ot.y1,$sd.y1)
  $ix2=[Math]::Min($ot.x2,$sd.x2); $iy2=[Math]::Min($ot.y2,$sd.y2)
  $ow=[Math]::Max(0,$ix2-$ix1); $oh=[Math]::Max(0,$iy2-$iy1)
  $area = $ot.w*$ot.h
  $cov = 0
  $bmp=[System.Drawing.Bitmap]::FromFile("$script:ART\DK_2_docked_ime.png")
  $hist=@{}
  for($y=$ot.y1;$y -lt $ot.y2;$y++){for($x=$ot.x1;$x -lt $ot.x2;$x++){
    if($x -ge $sd.x1 -and $x -lt $sd.x2 -and $y -ge $sd.y1 -and $y -lt $sd.y2){$cov++;continue}
    $c=$bmp.GetPixel($x,$y);$k="$($c.R),$($c.G),$($c.B)"
    if($hist.ContainsKey($k)){$hist[$k]++}else{$hist[$k]=1}}}
  $bmp.Dispose()
  "  DOCKED C1 overlapArea = $($ow*$oh)  (intersection ${ow}x${oh})  -> $(if(($ow*$oh) -eq 0){'PASS'}else{'FAIL'})"
  "  DOCKED C3 chip area=$area coveredBySend=$cov notCovered=$($area-$cov) -> $(if(($area-$cov) -gt 0){'PASS'}else{'FAIL'})"
  "  DOCKED chip.width=$($ot.w) (48 = not clipped)"
  "  DOCKED chip-minus-Send histogram (top 5):"
  $hist.GetEnumerator()|Sort-Object Value -Descending|Select-Object -First 5|ForEach-Object{"    $($_.Key) x$($_.Value)"}
  $raw = Get-Content "$script:ART\DK_2_docked_ime.xml" -Encoding UTF8
  $line = ($raw -join ' ')
  foreach ($l in @('Open terminal','Send')) {
    $m=[regex]::Match($line,'<node index="(\d+)"[^>]*content-desc="'+[regex]::Escape($l)+'"[^>]*bounds="(\[[^"]+\])"')
    if($m.Success){ "  DOCKED RAW $l : index=$($m.Groups[1].Value) bounds=$($m.Groups[2].Value)" }
  }
} else { "  DOCKED: cannot judge (chip or Send absent in docked form)" }

"### D3 undock by tapping the chip again (no BACK / no ESC)"
if ($ot) { Tap $ot; Start-Sleep -Seconds 5 }
$ns3 = Nodes (Dump 'DK_3_undocked')
$ot3 = $ns3 | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$sd3 = $ns3 | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
"  undocked: chip=$(if($ot3){"[$($ot3.x1),$($ot3.y1)][$($ot3.x2),$($ot3.y2)] $($ot3.w)x$($ot3.h)"}else{'ABSENT'})"
"  undocked: send=$(if($sd3){"[$($sd3.x1),$($sd3.y1)][$($sd3.x2),$($sd3.y2)] $($sd3.w)x$($sd3.h)"}else{'ABSENT'})"
"  ime=$(ImeState)"

"### D4 /sdcard residue"
adbx shell "ls -la /sdcard/" 2>&1 | Out-String
"DK done"
