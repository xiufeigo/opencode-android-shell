# v145 B.6: annotated forensic overlay for cell 5.
# Draws the two a11y rects in different colours over the real screenshot region,
# fills the overlap, and boxes the 2px residual sliver.  Zoom 8x, nearest-neighbour.
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
Add-Type -AssemblyName System.Drawing

# one-row form geometry, re-read live from the dump (no hardcoding)
[xml]$d = Get-Content "$script:ART\B_CELL5_600_ONEROW.xml" -Raw -Encoding UTF8
$nodes = @(); $i = 0
foreach ($n in $d.SelectNodes('//node')) {
  $b = $n.GetAttribute('bounds')
  if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $nodes += [pscustomobject]@{ idx=$i; desc=$n.GetAttribute('content-desc'); cls=$n.GetAttribute('class').Split('.')[-1]
      x1=[int]$Matches[1]; y1=[int]$Matches[2]; x2=[int]$Matches[3]; y2=[int]$Matches[4] }
    $i++
  }
}
$term = $nodes | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$send = $nodes | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
"  live re-read: terminal idx=$($term.idx) [$($term.x1),$($term.y1)][$($term.x2),$($term.y2)] | send idx=$($send.idx) [$($send.x1),$($send.y1)][$($send.x2),$($send.y2)]"

$img = [System.Drawing.Bitmap]::FromFile("$script:ART\B_CELL5_600_ONEROW.png")
$pad = 26
$cx = $term.x1 - $pad; $cy = $term.y1 - 14
$cw = ($term.x2 + $pad) - $cx; $ch = (($term.y2 + 14) - $cy)
$crop = New-Object System.Drawing.Bitmap $cw,$ch
$g = [System.Drawing.Graphics]::FromImage($crop)
$g.DrawImage($img, (New-Object System.Drawing.Rectangle 0,0,$cw,$ch), (New-Object System.Drawing.Rectangle $cx,$cy,$cw,$ch), [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$img.Dispose()

$S = 8
$big = New-Object System.Drawing.Bitmap ($cw*$S),($ch*$S)
$g2 = [System.Drawing.Graphics]::FromImage($big)
$g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g2.DrawImage($crop, 0, 0, $cw*$S, $ch*$S)
function ToRect { param($n) [pscustomobject]@{
  x=(($n.x1 - $cx)*$S); y=(($n.y1 - $cy)*$S)
  w=(($n.x2 - $n.x1)*$S);  h=(($n.y2 - $n.y1)*$S) } }
$rt = ToRect $term; $rs = ToRect $send

# overlap rect (intersection)
$ox = [Math]::Max($term.x1,$send.x1); $oy = [Math]::Max($term.y1,$send.y1)
$ox2 = [Math]::Min($term.x2,$send.x2); $oy2 = [Math]::Min($term.y2,$send.y2)
$ro = [pscustomobject]@{ x=(($ox-$cx)*$S); y=(($oy-$cy)*$S); w=(($ox2-$ox)*$S); h=(($oy2-$oy)*$S) }

$brushOverlap = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(110,255,0,255))
$g2.FillRectangle($brushOverlap, $ro.x, $ro.y, $ro.w, $ro.h)

$penTerm = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255,0,200,255)), 3
$penSend = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255,0,255,0)), 3
$g2.DrawRectangle($penTerm, $rt.x, $rt.y, $rt.w, $rt.h)
$g2.DrawRectangle($penSend, $rs.x, $rs.y, $rs.w, $rs.h)

# the 2px residual sliver: terminal.right - send.right
$sliverX = $term.x2 - $send.x2
$brushSliver = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255,255,215,0))
$g2.FillRectangle($brushSliver, (($send.x2 - $cx)*$S), $rt.y, ($sliverX*$S), $rt.h)
# the 1px bottom cap: terminal.bottom - send.bottom
$capY = $send.y2
$g2.FillRectangle($brushSliver, $rt.x, (($capY - $cy)*$S), $rt.w, ($S*1))

$font = New-Object System.Drawing.Font 'Consolas', 13
$fb = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
$fbb = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Black)
$g4 = [System.Drawing.Graphics]::FromImage($big)
$g4.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
$g4.FillRectangle($fbb, 2, 2, ($cw*$S)-4, 108)
$g4.DrawString("cell5 @ 600 one-row:  CYAN=Open terminal idx=$($term.idx) [$($term.x1),$($term.y1)][$($term.x2),$($term.y2)] (46x48)", $font, $fb, 8, 4)
$g4.DrawString("GREEN=Send idx=$($send.idx) [$($send.x1),$($send.y1)][$($send.x2),$($send.y2)] (48x48)  -> LATER in draw order = ON TOP", $font, $fb, 8, 24)
$g4.DrawString("MAGENTA=overlap 44x47=2068px^2 (covered).   YELLOW=residual: right sliver 2px x 47 + bottom cap 1px x 46 = 140px^2", $font, $fb, 8, 44)
$g4.DrawString("8x zoom, nearest-neighbour, region [$cx,$cy] ${cw}x${ch}  (red line = every 8 original px)", $font, $fb, 8, 64)
$g4.Dispose()

$out = "$script:ART\B_CELL5_annotated_8x.png"
$big.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
"  saved -> $out  ($($cw*$S)x$($ch*$S))"
"  sliver px = $($sliverX)  |  overlap = $($ro.w/$S)x$($ro.h/$S)"
"### overlay done"

