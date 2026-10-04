# v147: pull 600 criterion readings verbatim + pixel verdict on whether chip covers body text
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
Add-Type -AssemblyName System.Drawing

"### 600 set"
adbx shell wm density 160 | Out-Null
adbx shell wm size 1366x600 | Out-Null
Start-Sleep -Seconds 4
$ime = AssertIme '600 readings' | Select-Object -Last 1
$x = Dump 'FINAL_600'; $ns = Nodes $x
$ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
$ix1=[Math]::Max($ot.x1,$sd.x1); $iy1=[Math]::Max($ot.y1,$sd.y1)
$ix2=[Math]::Min($ot.x2,$sd.x2); $iy2=[Math]::Min($ot.y2,$sd.y2)
$ow=[Math]::Max(0,$ix2-$ix1); $oh=[Math]::Max(0,$iy2-$iy1)
$covW=[Math]::Max(0,[Math]::Min($ot.x2,$sd.x2)-[Math]::Max($ot.x1,$sd.x1))
$covH=[Math]::Max(0,[Math]::Min($ot.y2,$sd.y2)-[Math]::Max($ot.y1,$sd.y1))
$resW=$ot.w-$covW; $resH=$ot.h-$covH
$narrow=[Math]::Min($resW,$resH)
"  mInputShown=$ime"
"  chip=[$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)] $($ot.w)x$($ot.h) clickable=$($ot.clickable)"
"  send=[$($sd.x1),$($sd.y1)][$($sd.x2),$($sd.y2)] $($sd.w)x$($sd.h) clickable=$($sd.clickable)"
"  C1 overlapArea = $($ow*$oh)"
"  C2 residual=${resW}x${resH}  narrowestResidualPx=$narrow"
"  C4 chip.h=$($ot.h) send.h=$($sd.h)"
"  C6 clickable $($ot.clickable)/$($sd.clickable)"
$raw = Get-Content $x -Encoding UTF8
$r1 = ($raw | Select-String 'content-desc="Open terminal"' | Select-Object -First 1)
$r2 = ($raw | Select-String 'content-desc="Send"' | Select-Object -First 1)
"  RAW_OT  : $($r1.Line.Trim())"
"  RAW_SEND: $($r2.Line.Trim())"

"### pixel verdict: is the chip's own surface actually drawn, and is body text under it?"
$png = Shot 'FINAL_600'
$bmp = New-Object System.Drawing.Bitmap($png)
"  -- chip rect colour histogram (all pixels) --"
$h=@{}
for($y=$ot.y1;$y -lt $ot.y2;$y++){ for($x=$ot.x1;$x -lt $ot.x2;$x++){
  $c=$bmp.GetPixel($x,$y); $k="$($c.R),$($c.G),$($c.B)"
  if($h.ContainsKey($k)){$h[$k]++}else{$h[$k]=1} } }
$h.GetEnumerator()|Sort-Object Value -Descending|Select-Object -First 6|ForEach-Object{"    $($_.Key) x$($_.Value)"}

"  -- the 875x28 HorizontalScrollView body strip: rows y=201..229, x range under chip = $($ot.x1)..$($ot.x2) --"
"     (chip covers x=$($ot.x1)..$($ot.x2) for y=$($ot.y1)..$($ot.y2); scroll strip y=201..229 => full 28 rows inside chip band)"
$strip = $ns | Where-Object { $_.cls -eq 'HorizontalScrollView' } | Select-Object -First 1
"  scroll strip = [$($strip.x1),$($strip.y1)][$($strip.x2),$($strip.y2)] $($strip.w)x$($strip.h)"
"  -- compare a 48x28 patch of body strip UNDER the chip vs the SAME 48x28 patch 64px to the left (uncovered reference) --"
function PatchStat { param($bx,$by,$bw,$bh)
  $cols=@{}
  for($y=$by;$y -lt ($by+$bh);$y++){ for($x=$bx;$x -lt ($bx+$bw);$x++){
    $c=$bmp.GetPixel($x,$y); $k="$($c.R),$($c.G),$($c.B)"
    if($cols.ContainsKey($k)){$cols[$k]++}else{$cols[$k]=1} } }
  $tot=$bw*$bh
  $bg = ($cols.GetEnumerator()|Where-Object{$_.Key -eq '255,255,255' -or $_.Key -eq '250,250,250'}|ForEach-Object{$_.Value}|Measure-Object -Sum).Sum
  if(-not $bg){$bg=0}
  $top = ($cols.GetEnumerator()|Sort-Object Value -Descending|Select-Object -First 1)
  return "n=$tot  topColour=$($top.Key)x$($top.Value)  bgLike=$bg"
}
"    UNDER chip    x=$($ot.x1)..$($ot.x2) y=201..229 : $(PatchStat $ot.x1 201 48 28)"
"    reference     x=$($ot.x1-80)..$($ot.x2-80) y=201..229 : $(PatchStat ($ot.x1-80) 201 48 28)"
"    reference2    x=$($ot.x1-160)..$($ot.x2-160) y=201..229 : $(PatchStat ($ot.x1-160) 201 48 28)"
$bmp.Dispose()
"FINAL done"
