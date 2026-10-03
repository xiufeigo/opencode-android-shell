param([Parameter(Mandatory=$true)][string]$Path, [int]$ProbeX = 700)
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($Path)
$W = $bmp.Width; $H = $bmp.Height

function K($c) { "{0:X2}{1:X2}{2:X2}" -f $c.R, $c.G, $c.B }

# vertical: find border rows at ProbeX
$borders = @()
for ($y = 20; $y -lt $H; $y++) {
  $k = K $bmp.GetPixel($ProbeX, $y)
  if ($k -eq '363B45' -or $k -eq '4A4F58') { $borders += $y }
}
# fill runs of panel/card fill colour 1F2530
$runs = @(); $st = -1
for ($y = 20; $y -lt $H; $y++) {
  $k = K $bmp.GetPixel($ProbeX, $y)
  if ($k -eq '1F2530') { if ($st -lt 0) { $st = $y } }
  else { if ($st -ge 0) { $runs += "$st..$($y-1)"; $st = -1 } }
}
if ($st -ge 0) { $runs += "$st..$($H-1)" }

# keyboard top: first light row scanning right side
$kb = $null
for ($y = 20; $y -lt $H; $y++) { $c = $bmp.GetPixel(1250, $y); if ($c.R -gt 200 -and $c.G -gt 200 -and $c.B -gt 200) { $kb = $y; break } }

# card left/right: scan a row just inside the card top
$rowY = $null
if ($borders.Count -ge 2) { $rowY = $borders[1] + 1 }
$rowY = 92
$xs = @()
for ($x = 380; $x -le 1365; $x++) { $k = K $bmp.GetPixel($x, $rowY); if ($k -eq '363B45' -or $k -eq '4A4F58') { $xs += $x } }

"FILE            : $(Split-Path $Path -Leaf)"
"BORDERS_AT_X$ProbeX : $($borders -join ',')"
"FILL_RUNS       : $($runs -join ' | ')"
"CARD_X          : $($xs -join ',')"
"KBD_TOP         : $kb"
$bmp.Dispose()
