# v145 Task B: cell 5 (the 45-degree occlusion of the "Open terminal" chip) in the ONE-ROW form.
# Produces: z-order (document index) evidence, overlap rect + area, residual fragments + narrowest px,
#           pixel-level sliver scan, a zoomed crop, and the raw dump.
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
Add-Type -AssemblyName System.Drawing

# ---- indexed node parser (document order == draw order for a11y siblings) ----
function NodesIdx {
  param([string]$xml)
  [xml]$d = Get-Content $xml -Raw -Encoding UTF8
  $res = @(); $i = 0
  foreach ($n in $d.SelectNodes('//node')) {
    $b = $n.GetAttribute('bounds')
    if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { continue }
    $res += [pscustomobject]@{
      idx=$i; text=$n.GetAttribute('text'); desc=$n.GetAttribute('content-desc')
      cls=$n.GetAttribute('class').Split('.')[-1]; clickable=$n.GetAttribute('clickable')
      x1=[int]$Matches[1]; y1=[int]$Matches[2]; x2=[int]$Matches[3]; y2=[int]$Matches[4]
      w=([int]$Matches[3]-[int]$Matches[1]); h=([int]$Matches[4]-[int]$Matches[2])
    }
    $i++
  }
  return $res
}
function Inter { param($a,$b)
  $x1=[Math]::Max($a.x1,$b.x1); $y1=[Math]::Max($a.y1,$b.y1)
  $x2=[Math]::Min($a.x2,$b.x2); $y2=[Math]::Min($a.y2,$b.y2)
  if($x2 -le $x1 -or $y2 -le $y1){ return $null }
  [pscustomobject]@{x1=$x1;y1=$y1;x2=$x2;y2=$y2;w=($x2-$x1);h=($y2-$y1);area=(($x2-$x1)*($y2-$y1))}
}

"### B.0 one-row form: switch to 1366x600 (this is the form where cell 5 is a real defect)"
adbx shell wm density 160 | Out-Null
adbx shell wm size 1366x600 | Out-Null
Start-Sleep -Seconds 4
$ime = AssertIme "cell5 one-row form"
"  mInputShown=$ime  focus=$((Focus) -join '')  appPid=$(AppPid)"
"  wm: $((adbx shell wm size 2>&1 | Select-String 'Override') -join '')"

$dump = Dump 'B_CELL5_600_ONEROW'
$shot = Shot 'B_CELL5_600_ONEROW'
"  dump=$dump"
"  shot=$shot"
$ns = NodesIdx $dump

"### B.1 the two action nodes (document order == a11y/z order)"
$term = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$send = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
$et   = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
if (-not $term -or -not $send) { "  !! term=$($null -ne $term) send=$($null -ne $send)"; return }

"  idx=$($term.idx)  desc='Open terminal'  bounds=[$($term.x1),$($term.y1)][$($term.x2),$($term.y2)]  $($term.w)x$($term.h)  clickable=$($term.clickable)"
"  idx=$($send.idx)  desc='Send'            bounds=[$($send.x1),$($send.y1)][$($send.x2),$($send.y2)]  $($send.w)x$($send.h)  clickable=$($send.clickable)"
"  EditText         bounds=[$($et.x1),$($et.y1)][$($et.x2),$($et.y2)]  $($et.w)x$($et.h)"
"  Z-ORDER: idx(Send)=$($send.idx)  vs  idx(Open terminal)=$($term.idx)  =>  $(if($send.idx -gt $term.idx){'Send is LATER in draw order => Send is ON TOP (covers the chip)'}else{'Open terminal is LATER => chip is ON TOP'})"

"### B.2 overlap + residual fragments (terminal minus send, per axis)"
$iv = Inter $term $send
"  overlap rect = [$($iv.x1),$($iv.y1)][$($iv.x2),$($iv.y2)] = $($iv.w)x$($iv.h) = $($iv.area) px^2"
$termArea = $term.w * $term.h
"  chip area  = $($term.w)x$($term.h) = $termArea px^2"
"  COVERED by Send = $($iv.area) px^2   |   NOT covered (residual) = $termArea - $($iv.area) = $($termArea - $iv.area) px^2"
# residual = chip area not overlapped, split on the two axes
$leftCol  = [Math]::Max(0, $send.x1 - $term.x1)          # chip left of send
$rightCol = [Math]::Max(0, $term.x2 - $send.x2)           # chip right of send  <-- the suspected 2px sliver
$aboveRow = [Math]::Max(0, $send.y1 - $term.y1)          # chip above send
$belowRow = [Math]::Max(0, $term.y2 - $send.y2)          # chip below send
"  right sliver : terminal.right($($term.x2)) - send.right($($send.x2)) = $rightCol px  x $($iv.h) rows  => narrowest = $rightCol"
"  left  gap    : send.left($($send.x1)) - terminal.left($($term.x1)) = $leftCol px"
"  above cap    : send.top($($send.y1)) - terminal.top($($term.y1)) = $aboveRow px  x $($term.w) cols"
"  below cap    : terminal.bottom($($term.y2)) - send.bottom($($send.y2)) = $belowRow px  x $($term.w) cols"
$residual = ($rightCol * $iv.h) + ($aboveRow * $term.w) + ($leftCol * $term.h) + ($belowRow * $term.w)
"  residual px^2 (sum of disjoint slabs) = $residual   (check: chip - covered = $($termArea - $iv.area))"

"### B.3 pixel-level sliver scan on the screenshot (independent of a11y bounds)"
$img = [System.Drawing.Bitmap]::FromFile($shot)
"  screenshot = $($img.Width)x$($img.Height)"
function ScanLine { param([int]$y)
  $lo = [Math]::Max(0,$term.x1); $hi = [Math]::Min($img.Width-1,$term.x2)
  if ($y -lt 0 -or $y -ge $img.Height) { return @("      <row $y outside image $($img.Width)x$($img.Height)>") }
  $runs = @(); $prev = ''; $start = $lo
  for ($x = $lo; $x -le $hi; $x++) {
    $c = $img.GetPixel($x,$y).ToArgb()
    if ($c -ne $prev) {
      if ($prev -ne '') { $runs += "[$start..$($x-1)] w=$($x-$start)" }
      $prev = $c; $start = $x
    }
  }
  $runs += "[$start..$hi] w=$($hi-$start+1)"
  return $runs
}
"  scan y=196 (above send.top $($send.y1) => chip-only band):"
(ScanLine 196) | ForEach-Object { "      $_" }
"  scan y=210 (inside the OVERLAP band => whatever is ON TOP shows here):"
(ScanLine 210) | ForEach-Object { "      $_" }
"  scan y=244 (below chip.bottom $($term.y2), inside send => send-only band):"
(ScanLine 244) | ForEach-Object { "      $_" }

"### B.4 zoomed crop so the 2px sliver + occlusion are visible to the naked eye"
$cx = [Math]::Max(0,$term.x1-24); $cy = [Math]::Max(0,$term.y1-8)
$cw = [Math]::Min($img.Width-$cx, $term.x2-$cx+24); $ch = [Math]::Min($img.Height-$cy, 72)
$crop = New-Object System.Drawing.Bitmap $cw,$ch
$g = [System.Drawing.Graphics]::FromImage($crop)
$g.DrawImage($img, (New-Object System.Drawing.Rectangle 0,0,$cw,$ch), (New-Object System.Drawing.Rectangle $cx,$cy,$cw,$ch), [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$scale = 8
$big = New-Object System.Drawing.Bitmap ($cw*$scale),($ch*$scale)
$g2 = [System.Drawing.Graphics]::FromImage($big)
$g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g2.DrawImage($crop, 0, 0, $cw*$scale, $ch*$scale)
# gridlines every original pixel column, thin
$pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(70,255,0,0)), 1
for ($x=0; $x -le $cw; $x++) { if ($x % 8 -eq 0) { $g2.DrawLine($pen, $x*$scale, 0, $x*$scale, $ch*$scale) } }
$pen2 = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(70,0,0,255)), 1
for ($y=0; $y -le $ch; $y++) { if ($y % 8 -eq 0) { $g2.DrawLine($pen2, 0, $y*$scale, $cw*$scale, $y*$scale) } }
$g2.Dispose()
$bigPath = "$script:ART\B_CELL5_zoom8x.png"
$big.Save($bigPath, [System.Drawing.Imaging.ImageFormat]::Png)
$cropPath = "$script:ART\B_CELL5_crop.png"
$crop.Save($cropPath, [System.Drawing.Imaging.ImageFormat]::Png)
"  crop  ($cx,$cy) ${cw}x${ch}  -> $cropPath"
"  zoom8 -> $bigPath   (red = every 8 original px column, blue = every 8 original px row)"

"### B.5 same geometry at 768 (stacked form) for contrast"
$img.Dispose()
adbx shell wm size 1366x768 | Out-Null
Start-Sleep -Seconds 4
$d2 = Dump 'B_CELL5_768_STACKED'
$ns2 = NodesIdx $d2
$t2 = $ns2 | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$s2 = $ns2 | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
$e2 = $ns2 | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
"  Open terminal [$($t2.x1),$($t2.y1)][$($t2.x2),$($t2.y2)]  idx=$($t2.idx)"
"  Send           [$($s2.x1),$($s2.y1)][$($s2.x2),$($s2.y2)]  idx=$($s2.idx)"
"  EditText top   = $($e2.y1)"
$iv2 = Inter $t2 $s2
"  overlap at 768 = $(if($iv2){"$($iv2.w)x$($iv2.h) = $($iv2.area) px^2"}else{'NONE (disjoint) => 0% occlusion'})"
Shot 'B_CELL5_768_STACKED' | Out-Null
"### B done"
