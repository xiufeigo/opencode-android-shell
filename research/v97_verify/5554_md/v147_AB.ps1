# v147: corrected C2 metric + Task B (chip vs body text) + Task C (baselines + round-trip)
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
New-Item -ItemType Directory -Force -Path $script:ART | Out-Null

function SetDisp { param([string]$sz)
  adbx shell wm density 160 | Out-Null
  adbx shell wm size $sz | Out-Null
  Start-Sleep -Seconds 3
}

# CORRECTED: residual = chip box MINUS the part covered by Send.
# If Send covers nothing, the residual IS the whole chip box (48x48 -> min=48).
function Crit2 {
  param([string]$tag)
  $ime = AssertIme "criteria ($tag)" | Select-Object -Last 1
  $x = Dump $tag; $ns = Nodes $x
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
  if (-not $ot -or -not $sd) { return "  $tag : missing node" }

  $ix1=[Math]::Max($ot.x1,$sd.x1); $iy1=[Math]::Max($ot.y1,$sd.y1)
  $ix2=[Math]::Min($ot.x2,$sd.x2); $iy2=[Math]::Min($ot.y2,$sd.y2)
  $ow=[Math]::Max(0,$ix2-$ix1); $oh=[Math]::Max(0,$iy2-$iy1)
  $overlap=$ow*$oh
  $covW=[Math]::Min($ot.x2,$sd.x2)-[Math]::Max($ot.x1,$sd.x1)
  $covH=[Math]::Min($ot.y2,$sd.y2)-[Math]::Max($ot.y1,$sd.y1)
  $covW=[Math]::Max(0,$covW); $covH=[Math]::Max(0,$covH)
  $resW = $ot.w - $covW
  $resH = $ot.h - $covH
  $resArea = $resW*$resH
  if ($resArea -le 0) { $narrow = 0 } else { $narrow = [Math]::Min($resW,$resH) }
  $gapH = $sd.x1 - $ot.x2
  $gapV = $sd.y1 - $ot.y2

  $c1 = if ($overlap -eq 0) {'PASS'} else {'FAIL'}
  $c2 = if ($narrow -ge 16) {'PASS'} else {'FAIL'}
  $c4 = if ($ot.h -eq 48 -and $sd.h -eq 48) {'PASS'} else {'FAIL'}
  $c6 = if ($ot.clickable -eq 'true' -and $sd.clickable -eq 'true') {'PASS'} else {'FAIL'}

  "  === $tag === (mInputShown=$ime)"
  "   Open terminal: [$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)] $($ot.w)x$($ot.h) clickable=$($ot.clickable)"
  "   Send         : [$($sd.x1),$($sd.y1)][$($sd.x2),$($sd.y2)] $($sd.w)x$($sd.h) clickable=$($sd.clickable)"
  "   intersection = ${ow}x${oh}   overlapArea = $overlap"
  "   chip covered by Send = ${covW}x${covH}  => residual = ${resW}x${resH} = ${resArea}px2"
  "   C1 overlapArea=$overlap -> $c1"
  "   C2 narrowestResidualPx=$narrow (residual ${resW}x${resH}) -> $c2"
  "   C4 chip.h=$($ot.h) send.h=$($sd.h) -> $c4"
  "   C6 clickable $($ot.clickable)/$($sd.clickable) -> $c6"
  "   clearance: horizontal gap = $gapH px, vertical gap = $gapV px"
  # raw dump lines, matched on content-desc only
  $raw = Get-Content $x -Encoding UTF8
  $r1 = ($raw | Select-String 'content-desc="Open terminal"' | Select-Object -First 1)
  $r2 = ($raw | Select-String 'content-desc="Send"' | Select-Object -First 1)
  if ($r1) { "   RAW_OT  : $($r1.Line.Trim())" }
  if ($r2) { "   RAW_SEND: $($r2.Line.Trim())" }
}

"### A2. 768 criteria"
SetDisp '1366x768'
Crit2 'A2_768'

"### A3. 600 one-row criteria"
SetDisp '1366x600'
Crit2 'A3_600_ONEROW'

"### B7. chip (END_DP=64) vs message body text at 600"
$ns = Nodes (Dump 'B_600_body')
$ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
"  chip = [$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)]"
"  -- nodes whose rect intersects the chip rect (x-overlap AND y-overlap) --"
$hits = $ns | Where-Object {
  ($_.x1 -lt $ot.x2) -and ($_.x2 -gt $ot.x1) -and ($_.y1 -lt $ot.y2) -and ($_.y2 -gt $ot.y1)
}
foreach ($n in $hits) {
  $lbl = if ($n.text) { $n.text } elseif ($n.desc) { $n.desc } else { '(no label)' }
  if ($lbl.Length -gt 60) { $lbl = $lbl.Substring(0,60) }
  $ox = [Math]::Max(0, [Math]::Min($n.x2,$ot.x2) - [Math]::Max($n.x1,$ot.x1))
  $oy = [Math]::Max(0, [Math]::Min($n.y2,$ot.y2) - [Math]::Max($n.y1,$ot.y1))
  "    {0,-10} [{1},{2}][{3},{4}] {5}x{6}  overlapWithChip={7}x{8}  lbl='{9}'" -f $n.cls,$n.x1,$n.y1,$n.x2,$n.y2,$n.w,$n.h,$ox,$oy,$lbl
}
"  intersecting node count = $($hits.Count)"
"  -- all text-bearing nodes in the timeline band y=162..244 (body area) --"
$body = $ns | Where-Object { $_.y1 -ge 160 -and $_.y2 -le 250 -and ($_.text -or $_.desc) }
foreach ($n in $body) {
  $lbl = if ($n.text) { $n.text } else { $n.desc }
  if ($lbl.Length -gt 70) { $lbl = $lbl.Substring(0,70) }
  "    {0,-10} [{1},{2}][{3},{4}] {5}x{6} lbl='{7}'" -f $n.cls,$n.x1,$n.y1,$n.x2,$n.y2,$n.w,$n.h,$lbl
}
"  body-band node count = $($body.Count)"

"### B7b. crop chip region at 600 for visual judgement (with a bit of surrounding body)"
Add-Type -AssemblyName System.Drawing
$src=[System.Drawing.Image]::FromFile("$script:ART\v147_A_600_ONEROW.png")
$rx=1100;$ry=155;$rw=266;$rh=95
$bmp=New-Object System.Drawing.Bitmap($rw,$rh)
$g=[System.Drawing.Graphics]::FromImage($bmp)
$g.DrawImage($src,(New-Object System.Drawing.Rectangle(0,0,$rw,$rh)),(New-Object System.Drawing.Rectangle($rx,$ry,$rw,$rh)),[System.Drawing.GraphicsUnit]::Pixel)
$bmp.Save("$script:ART\v147_B_chip_600_crop.png",[System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose();$bmp.Dispose();$src.Dispose()
"  crop saved v147_B_chip_600_crop.png (x=$rx y=$ry w=$rw h=$rh)"
"BC done"
