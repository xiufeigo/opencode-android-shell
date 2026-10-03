param([string]$Path)
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
$W = $bmp.Width; $H = $bmp.Height
$BD = '363B45'

$ys = @()
for ($y = 40; $y -lt $H; $y++) {
  $c = $bmp.GetPixel(450, $y)
  if (("{0:X2}{1:X2}{2:X2}" -f $c.R,$c.G,$c.B) -eq $BD) { $ys += $y }
}
$cardTop = $null; $cardBot = $null
for ($i = 0; $i -lt $ys.Count - 1; $i++) {
  if (($ys[$i+1] - $ys[$i]) -ge 100) { $cardTop = $ys[$i]; $cardBot = $ys[$i+1]; break }
}

$xs = @()
if ($cardTop -ne $null) {
  $ry = $cardTop + 1
  for ($x = 380; $x -le 1020; $x++) {
    $c = $bmp.GetPixel($x, $ry)
    if (("{0:X2}{1:X2}{2:X2}" -f $c.R,$c.G,$c.B) -eq $BD) { $xs += $x }
  }
}
$cardL = if ($xs.Count -ge 1) { $xs[0] } else { $null }
$cardR = if ($xs.Count -ge 1) { $xs[-1] } else { $null }

$kbTop = $null
for ($y = 40; $y -lt $H; $y++) {
  $c = $bmp.GetPixel(1250, $y)
  if ($c.R -gt 200 -and $c.G -gt 200 -and $c.B -gt 200) { $kbTop = $y; break }
}

"FILE : $(Split-Path $Path -Leaf)"
"CARD : y $($cardTop)..$($cardBot)  h=$(if($cardTop -ne $null){$cardBot-$cardTop+1})   x $($cardL)..$($cardR)  w=$(if($cardL -ne $null){$cardR-$cardL+1})"
"KBD  : top=$kbTop   GAP_CARD_TO_KBD=$(if($kbTop -ne $null -and $cardBot -ne $null){$kbTop-$cardBot-1})"
"ALL_Y_BORDERS_AT_450 : $($ys -join ',')"
$bmp.Dispose()
