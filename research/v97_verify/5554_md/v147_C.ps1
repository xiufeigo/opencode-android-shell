# v147: corrected C2 + Task C baselines + resize round-trip x2 + logcat scan
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
New-Item -ItemType Directory -Force -Path $script:ART | Out-Null

function SetDisp { param([string]$sz)
  adbx shell wm density 160 | Out-Null
  adbx shell wm size $sz | Out-Null
  Start-Sleep -Seconds 3
}

# CORRECTED C2: coverage is zero unless the intersection is non-empty in BOTH axes.
function Crit2 { param([string]$tag)
  $ime = AssertIme "criteria ($tag)" | Select-Object -Last 1
  $x = Dump $tag; $ns = Nodes $x
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
  $sd = $ns | Where-Object { $_.desc -eq 'Send' } | Select-Object -First 1
  if (-not $ot -or -not $sd) { return "  $tag : MISSING node" }
  $ix1=[Math]::Max($ot.x1,$sd.x1); $iy1=[Math]::Max($ot.y1,$sd.y1)
  $ix2=[Math]::Min($ot.x2,$sd.x2); $iy2=[Math]::Min($ot.y2,$sd.y2)
  $ow=[Math]::Max(0,$ix2-$ix1); $oh=[Math]::Max(0,$iy2-$iy1)
  $overlap=$ow*$oh
  if ($ow -gt 0 -and $oh -gt 0) { $covW=$ow; $covH=$oh } else { $covW=0; $covH=0 }
  $resW=$ot.w-$covW; $resH=$ot.h-$covH
  $narrow= if (($resW*$resH) -le 0) { 0 } else { [Math]::Min($resW,$resH) }
  $c1=if($overlap -eq 0){'PASS'}else{'FAIL'}
  $c2=if($narrow -ge 16){'PASS'}else{'FAIL'}
  $c4=if($ot.h -eq 48 -and $sd.h -eq 48){'PASS'}else{'FAIL'}
  $c6=if($ot.clickable -eq 'true' -and $sd.clickable -eq 'true'){'PASS'}else{'FAIL'}
  "  === $tag === (mInputShown=$ime)"
  "   chip = [$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)] $($ot.w)x$($ot.h) clickable=$($ot.clickable)"
  "   send = [$($sd.x1),$($sd.y1)][$($sd.x2),$($sd.y2)] $($sd.w)x$($sd.h) clickable=$($sd.clickable)"
  "   intersection=[$ix1,$iy1][$ix2,$iy2] = ${ow}x${oh}"
  "   C1 overlapArea=$overlap -> $c1"
  "   C2 coverage=${covW}x${covH} residual=${resW}x${resH} narrowestResidualPx=$narrow -> $c2"
  "   C4 chip.h=$($ot.h) send.h=$($sd.h) -> $c4"
  "   C6 clickable $($ot.clickable)/$($sd.clickable) -> $c6"
  $raw = Get-Content $x -Encoding UTF8
  $r1=($raw|Select-String 'content-desc="Open terminal"'|Select-Object -First 1)
  $r2=($raw|Select-String 'content-desc="Send"'|Select-Object -First 1)
  if($r1){"   RAW_OT  : $($r1.Line.Trim())"}
  if($r2){"   RAW_SEND: $($r2.Line.Trim())"}
}

# v145 baseline measurement: (i) = unionBottom - paneBottom, union EXCLUDES 'Open terminal'
function Geo { param([string]$Tag)
  $ime = AssertIme "baseline ($Tag)" | Select-Object -Last 1
  $x = Dump $Tag; $ns = Nodes $x
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not $et) { return "  $Tag : NO EditText" }
  $kids = $ns | Where-Object {
    $_.y1 -ge ($et.y1 - 2) -and $_.y2 -le ($et.y2 + 60) -and
    -not ($_.text -eq 'Open terminal' -or $_.desc -eq 'Open terminal')
  }
  $uy1=[int](($kids|Measure-Object y1 -Minimum).Minimum)
  $uy2=[int](($kids|Measure-Object y2 -Maximum).Maximum)
  $unionPx=$uy2-$uy1
  $hosts = $ns | Where-Object {
    $_.x1 -le 2 -and $_.x2 -ge 1364 -and $_.y1 -le $et.y1 -and $_.y2 -ge ($uy2 - 2) -and $_.h -lt 900 -and $_.h -ge 40
  }
  $pane = $hosts | Sort-Object { $_.h } | Select-Object -First 1
  if (-not $pane) { return "  $Tag : NO pane host" }
  $paneH=$pane.h; $timelineBox=$paneH-48
  $readable = if ($paneH -ge 150) { $paneH - 96 } else { $paneH - 48 }
  $c1 = $uy2 - $pane.y2
  $q = $ns | Where-Object { $_.text -in @('Steer','Remove','Queue') -or $_.desc -in @('Steer','Remove','Queue') } | Select-Object -First 1
  $form = if ($q) { 'BAR-ON/CHIP' } else { 'BAR-OFF    ' }
  $barN = ($ns | Where-Object { $_.text -in @('Steer','Remove','Queue') -or $_.desc -in @('Steer','Remove','Queue') }).Count
  "  {0} {1} barNodes={2} pane=[{3},{4}][{5},{6}] paneH={7} timelineBox={8} readable={9} unionPx={10} unionBottom={11} paneBottom={12} (i)={13} mInputShown={14} appPid={15}" -f `
    $Tag,$form,$barN,$pane.x1,$pane.y1,$pane.x2,$pane.y2,$paneH,$timelineBox,$readable,$unionPx,$uy2,$pane.y2,$c1,$ime,(AppPid)
}

"### C0 corrected criteria at 768"
SetDisp '1366x768'
Crit2 'C0_768'

"### C1 corrected criteria at 600 (one-row form)"
SetDisp '1366x600'
Crit2 'C1_600_ONEROW'

"### C2 baseline 768"
SetDisp '1366x768'; Start-Sleep -Seconds 2
Geo 'C2_768_BAROFF'

"### C3 baseline 600"
SetDisp '1366x600'; Start-Sleep -Seconds 2
Geo 'C3_600_BAROFF'

"### C4 resize round-trip x2 (768->600->768)"
adbx logcat -c 2>&1 | Out-Null
"  logcat buffer cleared"
foreach ($r in 1..2) {
  SetDisp '1366x600'; Start-Sleep -Seconds 2
  Geo "C4_rt${r}_600"
  SetDisp '1366x768'; Start-Sleep -Seconds 2
  Geo "C4_rt${r}_768"
}

"### C5 restore 1366x768/160 explicitly"
adbx shell wm size 1366x768 | Out-Null
adbx shell wm density 160 | Out-Null
Start-Sleep -Seconds 2
"  wm size:    $((adbx shell wm size 2>&1 | Select-String 'Override') -join '')"
"  wm density: $((adbx shell wm density 2>&1 | Select-String 'Override') -join '')"

"### C6 logcat scan (-t, filtered by appPid; `$pid NOT used)"
$appPid = (AppPid)
"  appPid=$appPid"
if ($appPid) {
  $lg = & adb -s emulator-5554 logcat -t 4000 -v brief 2>&1 | Out-String
  $mine = ($lg -split "`n") | Where-Object { $_ -match "\s$appPid\s" }
  $hits = $mine | Select-String -Pattern 'FATAL|IllegalArgumentException|maxHeight'
  "  app lines=$($mine.Count)"
  if ($hits) { "  !! MATCHES:"; $hits | ForEach-Object { "    $_" } } else { "  FATAL|IllegalArgumentException|maxHeight = EMPTY (clean)" }
  $mine | Out-File -Encoding utf8 "$script:ART\C_logcat_apppid.log"
}
"### C7 ANR"
$anr = (adbx shell dumpsys window 2>&1 | Select-String 'Not Responding')
if ($anr) { "  !! ANR: $anr" } else { "  ANR: none" }
"C done"
