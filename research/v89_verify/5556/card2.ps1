param([string]$Path)
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
$X0 = 440; $X1 = 912

function IsBorder($b, $x, $y) {
  $c = $b.GetPixel($x, $y)
  $k = [int]$c.R * 65536 + [int]$c.G * 256 + [int]$c.B
  # 363B45, 4A4F58, 464B55
  return ($k -eq 0x363B45 -or $k -eq 0x4A4F58 -or $k -eq 0x464B55)
}

$rows = New-Object System.Collections.Generic.List[object]
for ($y = 30; $y -le 700; $y++) {
  $n = 0; $run = 0; $best = 0
  for ($x = $X0; $x -le $X1; $x++) {
    if (IsBorder $bmp $x $y) { $n++; $run++; if ($run -gt $best) { $best = $run } } else { $run = 0 }
  }
  if ($n -ge 400) { $rows.Add([pscustomobject]@{ Y = $y; N = $n; Best = $best }) }
}

"FILE  : $(Split-Path $Path -Leaf)"
"HROWS : " + (($rows | ForEach-Object { "$($_.Y)(w=$($_.Best))" }) -join ' ')

# card border rows: run 455..470 (panel chrome lines are the full 473)
$card = @($rows | Where-Object { $_.Best -ge 455 -and $_.Best -le 470 })
if ($card.Count -gt 0) {
  $top = $card[0].Y; $bot = $card[-1].Y
  $xs = @()
  for ($x = 420; $x -le 1180; $x++) { if (IsBorder $bmp $x $top) { $xs += $x } }
  $bs = 0; $bl = 0; $cur = 1
  for ($i = 1; $i -lt $xs.Count; $i++) {
    if ($xs[$i] -eq $xs[$i-1] + 1) { $cur++ } else { if ($cur -gt $bl) { $bl = $cur; $bs = $xs[$i-$cur] }; $cur = 1 }
  }
  if ($cur -gt $bl) { $bl = $cur; $bs = $xs[$xs.Count-$cur] }
  "CARD  : top=$top bot=$bot  h=$($bot-$top+1)   x=$bs..$($bs+$bl-1) w=$bl"
} else { "CARD  : (none)" }

# footer band = the 80px above the card's own bottom edge (footer is pinned there)
$fbTop = if ($card.Count -gt 0) { $card[-1].Y - 80 } else { 540 }
$fbBot = if ($card.Count -gt 0) { $card[-1].Y - 25 } else { 615 }
$btnTop = $null; $btnBot = $null; $btnL = $null; $btnR = $null
for ($y = $fbTop; $y -le $fbBot; $y++) {
  for ($x = 700; $x -le 940; $x++) {
    $c = $bmp.GetPixel($x, $y)
    $k = [int]$c.R * 65536 + [int]$c.G * 256 + [int]$c.B
    if ($k -eq 0x67B1C6 -or $k -eq 0x505661) {
      if ($btnTop -eq $null -or $y -lt $btnTop) { $btnTop = $y }
      if ($btnBot -eq $null -or $y -gt $btnBot) { $btnBot = $y }
      if ($btnL -eq $null -or $x -lt $btnL) { $btnL = $x }
      if ($btnR -eq $null -or $x -gt $btnR) { $btnR = $x }
    }
  }
}
if ($btnTop -ne $null) {
  "BTN   : primary fill present  y=$btnTop..$btnBot (h=$($btnBot-$btnTop+1))  x=$btnL..$btnR"
} else { "BTN   : primary fill NOT FOUND in y$fbTop..$fbBot" }

# light text pixels in the whole footer band
$lp = 0
for ($y = $fbTop; $y -le $fbBot; $y++) {
  for ($x = 700; $x -le 940; $x++) {
    $c = $bmp.GetPixel($x, $y)
    $lum = 0.299*$c.R + 0.587*$c.G + 0.114*$c.B
    if ($lum -gt 120) { $lp++ }
  }
}
"FOOTPX: light pixels in [700,545]-[940,612] = $lp"

$kt = $null
for ($y = 30; $y -lt 768; $y++) {
  $c = $bmp.GetPixel(1250, $y)
  if ($c.R -gt 200 -and $c.G -gt 200 -and $c.B -gt 200) { $kt = $y; break }
}
if ($kt -ne $null -and $card.Count -gt 0) { "KBD   : top=$kt GAP=$($kt - $card[-1].Y - 1)" } else { "KBD   : DOWN (top=$kt)" }
$bmp.Dispose()
