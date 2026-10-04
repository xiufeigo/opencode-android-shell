# v147: DOCKED form readings at 768 and 600 (criteria 1/3), then restore + cleanup
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
Add-Type -AssemblyName System.Drawing

function SetDisp { param([string]$sz)
  adbx shell wm density 160 | Out-Null; adbx shell wm size $sz | Out-Null; Start-Sleep -Seconds 3
}
function ReadDock { param([string]$tag)
  $ime = AssertIme "docked ($tag)" | Select-Object -Last 1
  $png = Shot $tag
  $dmpPath = Dump $tag; $ns = Nodes $dmpPath
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
  if (-not $ot) { return "  $tag : chip ABSENT (not docked)" }
  # pane width from the widest mid-height host that contains the chip
  $ix1=[Math]::Max($ot.x1,$sd.x1); $iy1=[Math]::Max($ot.y1,$sd.y1)
  $ix2=[Math]::Min($ot.x2,$sd.x2); $iy2=[Math]::Min($ot.y2,$sd.y2)
  $ow=[Math]::Max(0,$ix2-$ix1); $oh=[Math]::Max(0,$iy2-$iy1)
  $bmp=[System.Drawing.Bitmap]::FromFile($png)
  $area=$ot.w*$ot.h; $cov=0; $hist=@{}
  for($y=$ot.y1;$y -lt $ot.y2;$y++){for($x=$ot.x1;$x -lt $ot.x2;$x++){
    if($x -ge $sd.x1 -and $x -lt $sd.x2 -and $y -ge $sd.y1 -and $y -lt $sd.y2){$cov++;continue}
    $c=$bmp.GetPixel($x,$y);$k="$($c.R),$($c.G),$($c.B)"
    if($hist.ContainsKey($k)){$hist[$k]++}else{$hist[$k]=1}}}
  $bmp.Dispose()
  "  === $tag (DOCKED, mInputShown=$ime) ==="
  "   chip = [$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)] $($ot.w)x$($ot.h) clickable=$($ot.clickable)"
  "   send = $(if($sd){"[$($sd.x1),$($sd.y1)][$($sd.x2),$($sd.y2)] $($sd.w)x$($sd.h) clickable=$($sd.clickable)"}else{'ABSENT'})"
  "   inferred paneRight = chip.right + 54 = $($ot.x2 + 54)  (END_DP=64, touch right = paneRight-54)"
  "   C1 overlapArea = $($ow*$oh) (intersection ${ow}x${oh}) -> $(if(($ow*$oh) -eq 0){'PASS'}else{'FAIL'})"
  "   C3 chip area=$area coveredBySend=$cov notCovered=$($area-$cov) -> $(if(($area-$cov) -gt 0){'PASS'}else{'FAIL'})"
  "   C4 chip.h=$($ot.h) send.h=$(if($sd){$sd.h}else{'n/a'})"
  "   C6 clickable chip=$($ot.clickable) send=$(if($sd){$sd.clickable}else{'n/a'})"
  "   chip-minus-Send histogram (top 4): " + (($hist.GetEnumerator()|Sort-Object Value -Descending|Select-Object -First 4|ForEach-Object{"$($_.Key)x$($_.Value)"}) -join '  ')
  $line = (Get-Content $dmpPath -Encoding UTF8) -join ' '
  foreach ($l in @('Open terminal','Send')) {
    $m=[regex]::Match($line,'<node index="(\d+)"[^>]*content-desc="'+[regex]::Escape($l)+'"[^>]*bounds="(\[[^"]+\])"')
    if($m.Success){ "   RAW $l : index=$($m.Groups[1].Value) bounds=$($m.Groups[2].Value)" }
  }
}

"### E0 make sure we ARE docked (tap chip if currently undocked)"
SetDisp '1366x768'
$ns = Nodes (Dump 'E0_probe')
$ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
$docked = ($ot.x2 -lt 1200)
"  chip=[$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)]  send=$(if($sd){"[$($sd.x1),$($sd.y1)][$($sd.x2),$($sd.y2)]"}else{'ABSENT'})  inferredDocked=$docked"
if (-not $docked) { Tap $ot; Start-Sleep -Seconds 5; "  tapped chip to dock" }

"### E1 docked at 768"
$et = (Nodes (Dump 'E1_pre')) | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
if ($et) { Tap $et; Start-Sleep -Seconds 4 }
ReadDock 'E1_DOCK_768'

"### E2 docked at 600"
SetDisp '1366x600'
$et2 = (Nodes (Dump 'E2_pre')) | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
if ($et2) { Tap $et2; Start-Sleep -Seconds 4 }
ReadDock 'E2_DOCK_600'

"### E3 undock + restore 1366x768/160"
$ns3 = Nodes (Dump 'E3_probe')
$ot3 = $ns3 | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
if ($ot3 -and $ot3.x2 -lt 1200) { Tap $ot3; Start-Sleep -Seconds 5; "  undocked" } else { "  already undocked" }
adbx shell wm size 1366x768 | Out-Null
adbx shell wm density 160 | Out-Null
Start-Sleep -Seconds 3
"  wm size:    $((adbx shell wm size 2>&1 | Select-String 'Override') -join '')"
"  wm density: $((adbx shell wm density 2>&1 | Select-String 'Override') -join '')"
$nsf = Nodes (Dump 'E3_final')
$otf = $nsf | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
$sdf = $nsf | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
"  FINAL chip=$(if($otf){"[$($otf.x1),$($otf.y1)][$($otf.x2),$($otf.y2)] $($otf.w)x$($otf.h)"}else{'ABSENT'})"
"  FINAL send=$(if($sdf){"[$($sdf.x1),$($sdf.y1)][$($sdf.x2),$($sdf.y2)] $($sdf.w)x$($sdf.h)"}else{'ABSENT'})"

"### E4 logcat scan"
$appPid = (AppPid)
$lg = & adb -s emulator-5554 logcat -t 4000 -v brief 2>&1 | Out-String
$mine = ($lg -split "`n") | Where-Object { $_ -match "\s$appPid\s" }
$hits = $mine | Select-String -Pattern 'FATAL|IllegalArgumentException|maxHeight'
"  appPid=$appPid  appLines=$($mine.Count)  matches=$(if($hits){$hits.Count}else{0})"
if ($hits) { $hits | Select-Object -First 5 | ForEach-Object { "    $_" } }
"E done"
