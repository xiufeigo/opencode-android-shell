# v145 B.7: wider unannotated context crop so the terminal chip and Send can be told apart visually.
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
Add-Type -AssemblyName System.Drawing

$img = [System.Drawing.Bitmap]::FromFile("$script:ART\B_CELL5_600_ONEROW.png")
"  source = $($img.Width)x$($img.Height)"

# context crop: whole right end of the composer + the chip
$cx=1200; $cy=150; $cw=166; $ch=120
$crop = New-Object System.Drawing.Bitmap $cw,$ch
$g = [System.Drawing.Graphics]::FromImage($crop)
$g.DrawImage($img, (New-Object System.Drawing.Rectangle 0,0,$cw,$ch), (New-Object System.Drawing.Rectangle $cx,$cy,$cw,$ch), [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$S=6
$big = New-Object System.Drawing.Bitmap ($cw*$S),($ch*$S)
$g2 = [System.Drawing.Graphics]::FromImage($big)
$g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g2.DrawImage($crop,0,0,$cw*$S,$ch*$S)
$pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(60,255,0,0)),1
for($x=0;$x -le $cw;$x++){ if($x % 10 -eq 0){ $g2.DrawLine($pen,$x*$S,0,$x*$S,$ch*$S) } }
$pen2 = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(60,0,0,255)),1
for($y=0;$y -le $ch;$y++){ if($y % 10 -eq 0){ $g2.DrawLine($pen2,0,$y*$S,$cw*$S,$y*$S) } }
$font = New-Object System.Drawing.Font 'Consolas',11
$fbb = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Black)
$fb  = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
$g4 = [System.Drawing.Graphics]::FromImage($big)
$g4.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
$g4.FillRectangle($fbb,0,0,$cw*$S,34)
$g4.DrawString("600 one-row, real pixels. region [$cx,$cy] ${cw}x${ch} @${S}x. grid=every 10 orig px",$font,$fb,4,4)
# a11y boxes: terminal (cyan) and send (green), mapped into this crop
[xml]$d = Get-Content "$script:ART\B_CELL5_600_ONEROW.xml" -Raw -Encoding UTF8
$nodes=@();$i=0
foreach($n in $d.SelectNodes('//node')){ $b=$n.GetAttribute('bounds')
  if($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){ $nodes+=[pscustomobject]@{idx=$i;desc=$n.GetAttribute('content-desc');x1=[int]$Matches[1];y1=[int]$Matches[2];x2=[int]$Matches[3];y2=[int]$Matches[4]}; $i++ } }
$term=$nodes|Where-Object{$_.desc -eq 'Open terminal'}|Select-Object -First 1
$send=$nodes|Where-Object{$_.desc -eq 'Send'}|Select-Object -First 1
$pt = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255,0,220,255)),2
$ps = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255,0,255,0)),2
$g4.DrawRectangle($pt, (($term.x1-$cx)*$S), (($term.y1-$cy)*$S), (($term.x2-$term.x1)*$S), (($term.y2-$term.y1)*$S))
$g4.DrawRectangle($ps, (($send.x1-$cx)*$S), (($send.y1-$cy)*$S), (($send.x2-$send.x1)*$S), (($send.y2-$send.y1)*$S))
$g4.DrawString("cyan=Open terminal [$($term.x1),$($term.y1)][$($term.x2),$($term.y2)] idx=$($term.idx)",$font,$fb,4,40)
$g4.DrawString("green=Send           [$($send.x1),$($send.y1)][$($send.x2),$($send.y2)] idx=$($send.idx)",$font,$fb,4,58)
$g4.Dispose()
$out="$script:ART\B_CELL5_context_6x.png"
$big.Save($out,[System.Drawing.Imaging.ImageFormat]::Png)
"  saved -> $out ($($cw*$S)x$($ch*$S))"

# sample specific pixels to identify surfaces
"  --- pixel probes (orig coords) ---"
foreach($pt2 in @(@(1340,215),@(1330,215),@(1350,215),@(1360,215),@(1365,215),@(1364,215),@(1365,196),@(1340,196),@(1340,244),@(1365,244))){
  $c=$img.GetPixel($pt2[0],$pt2[1])
  "    ($($pt2[0]),$($pt2[1])) = R$($c.R) G$($c.G) B$($c.B)"
}
$img.Dispose()
"### context done"
