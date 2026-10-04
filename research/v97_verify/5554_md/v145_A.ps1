# v145 Task A: re-verify the four baseline numbers at 1366x768 and 1366x600, bar-off form,
# plus resize round-trip x2.  logcat always with -t, filtered by $appPid (never $pid).
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
$script:PKG = 'com.xiufeigo.opencodeshell'

function Geo {
  param([string]$Tag)
  try {
  $ime = AssertIme "reading geometry ($Tag)" | Select-Object -Last 1
  $x = Dump $Tag; $ns = Nodes $x
  Shot $Tag | Out-Null
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not $et) { return "  $Tag : NO EditText" }
  $kids = $ns | Where-Object {
    $_.y1 -ge ($et.y1 - 2) -and $_.y2 -le ($et.y2 + 60) -and
    -not ($_.text -eq 'Open terminal' -or $_.desc -eq 'Open terminal')
  }
  $uy1 = [int](($kids | Measure-Object y1 -Minimum).Minimum)
  $uy2 = [int](($kids | Measure-Object y2 -Maximum).Maximum)
  $unionPx = $uy2 - $uy1
  $hosts = $ns | Where-Object {
    $_.x1 -le 2 -and $_.x2 -ge 1364 -and $_.y1 -le $et.y1 -and $_.y2 -ge ($uy2 - 2) -and $_.h -lt 900 -and $_.h -ge 40
  }
  $pane = $hosts | Sort-Object { $_.h } | Select-Object -First 1
  if (-not $pane) { return "  $Tag : NO pane host" }
  $paneH = $pane.h
  $timelineBox = $paneH - 48
  $readable = if ($paneH -ge 150) { $paneH - 96 } else { $paneH - 48 }
  $c1 = $uy2 - $pane.y2
  $q = $ns | Where-Object { $_.text -eq 'Steer' -or $_.text -eq 'Remove' -or $_.text -eq 'Queue' } | Select-Object -First 1
  $form = if ($q) { 'BAR-ON/CHIP' } else { 'BAR-OFF    ' }
  $ot = $ns | Where-Object { $_.desc -eq 'Open terminal' } | Select-Object -First 1
  $otS = if ($ot) { "[$($ot.x1),$($ot.y1)][$($ot.x2),$($ot.y2)] $($ot.w)x$($ot.h)" } else { '-' }
  $line = "  {0} {1} pane=[{2},{3}][{4},{5}] paneH={6} timelineBox={7} readable={8} unionPx={9} unionBottom={10} paneBottom={11} (i)={12} mInputShown={13} OpenTerm={14}" -f `
    $Tag, $form, $pane.x1, $pane.y1, $pane.x2, $pane.y2, $paneH, $timelineBox, $readable, $unionPx, $uy2, $pane.y2, $c1, $ime, $otS
  Write-Output $line
  } catch {
  Write-Output "  $Tag : MEASURE ERROR -> $($_.Exception.Message)"
  }
}

function SetDisp { param([string]$sz)
  adbx shell wm density 160 | Out-Null
  adbx shell wm size $sz | Out-Null
  Start-Sleep -Seconds 3
}

"### A.0 gate"
"  lastUpdateTime: $((adbx shell dumpsys package $script:PKG 2>&1 | Select-String 'lastUpdateTime') -join '')"
"  wm size:    $((adbx shell wm size 2>&1 | Select-String 'Override') -join '')"
"  wm density: $((adbx shell wm density 2>&1 | Select-String 'Override') -join '')"
"  reverse:    $((adbx reverse --list 2>&1 | Out-String).Trim())"
"  focus:      $((Focus) -join '')"
"  appPid:     $(AppPid)"
"  ime:        $(ImeState)"

"### A.1 confirm bar-off form (no Steer/Remove/Queue in tree)"
$ns = Nodes (Dump 'A0_pre')
$bar = $ns | Where-Object { $_.text -in @('Steer','Remove','Queue') }
"  bar nodes: $($bar.Count)  (0 == bar-off)"

"### A.2 768 bar-off"
SetDisp '1366x768'; Start-Sleep -Seconds 2
Geo 'A_768_BAROFF'

"### A.3 600 bar-off"
SetDisp '1366x600'; Start-Sleep -Seconds 2
Geo 'A_600_BAROFF'

"### A.4 resize round-trip x2 (768 -> 600 -> 768)"
adbx logcat -c 2>&1 | Out-Null
"  logcat buffer cleared"
foreach ($round in 1..2) {
  SetDisp '1366x600'; Start-Sleep -Seconds 2
  Geo "A_rt${round}_600"
  "    RT${round}->600 appPid=$(AppPid)"
  SetDisp '1366x768'; Start-Sleep -Seconds 2
  Geo "A_rt${round}_768"
  "    RT${round}->768 appPid=$(AppPid)"
}

"### A.5 restore 1366x768/160 explicitly"
adbx shell wm size 1366x768 | Out-Null
adbx shell wm density 160 | Out-Null
Start-Sleep -Seconds 2
"  wm size:    $((adbx shell wm size 2>&1 | Select-String 'Override') -join '')"
"  wm density: $((adbx shell wm density 2>&1 | Select-String 'Override') -join '')"

"### A.6 logcat scan (app pid, -t)"
$appPid = (AppPid)
"  appPid=$appPid   (filter used: --pid style whitespace match; `$pid NOT used)"
if ($appPid) {
  $lg = & adb -s $script:D logcat -t 4000 -v brief 2>&1 | Out-String
  $mine = ($lg -split "`n") | Where-Object { $_ -match "\s$appPid\s" }
  $hits = $mine | Select-String -Pattern 'FATAL|IllegalArgumentException|maxHeight'
  "  app lines=$($mine.Count)"
  if ($hits) { "  !! MATCHES:"; $hits | ForEach-Object { "    $_" } } else { "  FATAL|IllegalArgumentException|maxHeight = EMPTY  (clean)" }
  $mine | Out-File -Encoding utf8 "$script:ART\A_logcat_apppid.log"
}
"### A.7 ANR check"
$anr = (adbx shell dumpsys window 2>&1 | Select-String 'Not Responding')
if ($anr) { "  !! ANR: $anr" } else { "  ANR: none" }
"### A done"

