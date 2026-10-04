# v147 Task A: six criteria on the one-row form (1366x600), incl. pixel-level chip visibility
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
New-Item -ItemType Directory -Force -Path $script:ART | Out-Null

function SetDisp { param([string]$sz)
  adbx shell wm density 160 | Out-Null
  adbx shell wm size $sz | Out-Null
  Start-Sleep -Seconds 3
}

# ---- geometry of the two targets + criterion math, all parsed at runtime ----
function Crit {
  param([string]$tag, [string]$shotName)
  $ime = AssertIme "criteria ($tag)" | Select-Object -Last 1
  $x = Dump $tag; $ns = Nodes $x
  $png = Shot $shotName
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' -or $_.text -eq 'Open terminal' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' -or $_.text -eq 'Send' } | Select-Object -First 1
  if (-not $ot) { return "  $tag : NO 'Open terminal' node" }
  if (-not $sd) { return "  $tag : NO 'Send' node" }

  # overlap area
  $ix1 = [Math]::Max($ot.x1,$sd.x1); $iy1 = [Math]::Max($ot.y1,$sd.y1)
  $ix2 = [Math]::Min($ot.x2,$sd.x2); $iy2 = [Math]::Min($ot.y2,$sd.y2)
  $ow = [Math]::Max(0, $ix2-$ix1); $oh = [Math]::Max(0, $iy2-$iy1)
  $overlap = $ow * $oh
  # narrowest residual dimension of chip minus Send
  $r1w = $ot.x1 - $sd.x2; $r2w = $sd.x1 - $ot.x2
  $r1h = $ot.y1 - $sd.y2; $r2h = $sd.y1 - $ot.y2
  $segs = @()
  if ($r1w -gt 0) { $segs += ,@('L', $ot.h, $r1w) }
  if ($r2w -gt 0) { $segs += ,@('R', $ot.h, $r2w) }
  if ($r1h -gt 0) { $segs += ,@('T', $ot.w, $r1h) }
  if ($r2h -gt 0) { $segs += ,@('B', $ot.w, $r2h) }
  if ($segs.Count -eq 0) { $narrow = 0 } else {
    $dims = @()
    $segs | ForEach-Object {
      $dims += [int]$_[1]
      $dims += [int]$_[2]
      "   seg $($_[0]) = $($_[1]) x $($_[2])"
    }
    $narrow = ($dims | Measure-Object -Minimum).Minimum
  }
  $segsTxt = ($segs | ForEach-Object { "$($_[0])$($_[1])x$($_[2])" }) -join ', '
  $c1 = if ($overlap -eq 0) {'PASS'} else {'FAIL'}
  $c2 = if ($narrow -ge 16) {'PASS'} else {'FAIL'}
  $c4 = if ($ot.h -eq 48 -and $sd.h -eq 48) {'PASS'} else {'FAIL'}
  $c6 = if ($ot.clickable -eq 'true' -and $sd.clickable -eq 'true') {'PASS'} else {'FAIL'}

  # dump raw lines for the two targets
  $raw = Get-Content $x -Encoding UTF8
  $rawOt = ($raw | Select-String -Pattern 'Open terminal' | Select-Object -First 1).Line
  $rawSd = ($raw | Select-String -Pattern '"Send"|content-desc="Send"' | Select-Object -First 1).Line

  "  === $tag ==="
  "   mInputShown=$ime  nodes=$($ns.Count)"
  "   Open terminal: [$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)] $($ot.w)x$($ot.h) clickable=$($ot.clickable) enabled=$($ot.enabled)"
  "   Send         : [$($sd.x1),$($sd.y1)][$($sd.x2),$($sd.y2)] $($sd.w)x$($sd.h) clickable=$($sd.clickable) enabled=$($sd.enabled)"
  "   intersection : [$ix1,$iy1][$ix2,$iy2] = ${ow}x${oh}"
  "   C1 overlapArea          = $overlap   -> $c1"
  "   C2 narrowestResidualPx  = $narrow (segs: $segsTxt)  -> $c2"
  "   C4 both48               = chip=$($ot.h) send=$($sd.h)  -> $c4"
  "   C6 both clickable       = $($ot.clickable)/$($sd.clickable)  -> $c6"
  "   PNG=$png"
  "   RAW_OT: $($rawOt.Trim())"
  "   RAW_SEND: $($rawSd.Trim())"
  return $null
}

"### A.0 768 chip reading (criterion 5 evidence: vertical separation)"
SetDisp '1366x768'
Crit 'A_768' 'v147_A_768'

"### A.1 600 one-row form -> the six criteria"
SetDisp '1366x600'
Crit 'A_600_ONEROW' 'v147_A_600_ONEROW'

"### A.2 crop 600 chip+send region for visual judgement"
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile("$script:ART\v147_A_600_ONEROW.png")
# chip [1264,192][1312,240] and Send [1316,191][1364,239]: crop generous window
$rx=1240; $ry=170; $rw=140; $rh=90
$rect = New-Object System.Drawing.Rectangle($rx,$ry,$rw,$rh)
$bmp = New-Object System.Drawing.Bitmap($rw,$rh)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.DrawImage($src, (New-Object System.Drawing.Rectangle(0,0,$rw,$rh)), $rect, [System.Drawing.GraphicsUnit]::Pixel)
$out = "$script:ART\v147_A_600_crop.png"
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose(); $src.Dispose()
"  crop saved: $out  (source region x=$rx y=$ry w=$rw h=$rh)"

"### A.3 pixel scan inside chip bounds, with and without Send's rectangle"
$src2 = [System.Drawing.Image]::FromFile("$script:ART\v147_A_600_ONEROW.png")
$ns2 = Nodes (Dump 'A_600_rescan')
$ot2 = $ns2 | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$sd2 = $ns2 | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
"  chip=[$($ot2.x1),$($ot2.y1)][$($ot2.x2),$($ot2.y2)]  send=[$($sd2.x1),$($sd2.y1)][$($sd2.x2),$($sd2.y2)]"
$bw = $ot2.x2-$ot2.x1; $bh = $ot2.y2-$ot2.y1
$bmp2 = New-Object System.Drawing.Bitmap($src2)
$chipNotSend = 0; $chipTotal=0; $hist=@{}
for ($y=$ot2.y1; $y -lt $ot2.y2; $y++) {
  for ($x=$ot2.x1; $x -lt $ot2.x2; $x++) {
    $c = $bmp2.GetPixel($x,$y)
    $chipTotal++
    $inSend = ($x -ge $sd2.x1 -and $x -lt $sd2.x2 -and $y -ge $sd2.y1 -and $y -lt $sd2.y2)
    if (-not $inSend) {
      $chipNotSend++
      $k = "$($c.R),$($c.G),$($c.B)"
      if ($hist.ContainsKey($k)) { $hist[$k]++ } else { $hist[$k]=1 }
    }
  }
}
"  chip area = $chipTotal px;  chip NOT covered by Send = $chipNotSend px"
"  colour histogram of chip-minus-Send (top 8):"
$hist.GetEnumerator() | Sort-Object -Property Value -Descending | Select-Object -First 8 | ForEach-Object { "    $($_.Key)  x$($_.Value)" }
$bmp2.Dispose(); $src2.Dispose()
"A done"
