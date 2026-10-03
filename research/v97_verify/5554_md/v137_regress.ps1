# v137 Task A2: no-regression recheck.
#   768 bar-off : timeline box 140 / readable 92 / (i) -10
#   600 bar-off : (i) -12 / readable 51
#   resize round-trip x2, logcat by app pid, FATAL|IllegalArgumentException|maxHeight must be empty
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"
$script:PKG = 'com.xiufeigo.opencodeshell'

function Measure {
  param([string]$Tag)
  $ime = AssertIme "reading geometry ($Tag)"
  $x = Dump $Tag; $ns = Nodes $x
  Shot $Tag | Out-Null
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not $et) { return "  $Tag : NO EditText" }
  $kids = $ns | Where-Object {
    $_.y1 -ge ($et.y1 - 2) -and $_.y2 -le ($et.y2 + 60) -and
    -not ($_.text -eq 'Open terminal' -or $_.desc -eq 'Open terminal')
  }
  $uy1 = ($kids | Measure-Object y1 -Minimum).Minimum
  $uy2 = ($kids | Measure-Object y2 -Maximum).Maximum
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
  $q = $ns | Where-Object { $_.text -eq 'Steer' -or $_.text -eq 'Remove' } | Select-Object -First 1
  $form = if ($q) { 'BAR-ON ' } else { 'BAR-OFF' }
  $etTop = $et.y1
  $btn = $ns | Where-Object { ($_.text -eq 'Add' -or $_.text -eq 'Stop' -or $_.text -eq 'Send') -and $_.h -eq 48 } | Select-Object -First 1
  $align = if ($btn) { $etTop - $btn.y1 } else { 'n/a' }
  return ("  {0,-18} {1} paneH={2} timelineBox={3} readable={4} unionPx={5} unionBottom={6} paneBottom={7} (i)={8} etTop={9} btnTop={10} topDelta={11} mInputShown={12}" -f `
    $Tag, $form, $paneH, $timelineBox, $readable, $unionPx, $uy2, $pane.y2, $c1, $etTop, $(if($btn){$btn.y1}else{'-'}), $align, $ime)
}

function SetDisp { param([string]$sz)
  adbx shell wm density 160 | Out-Null
  adbx shell wm size $sz | Out-Null
  Start-Sleep -Seconds 3
}

"### A2.1 clear the queue (only self-created E2ECHK5554u1v rows)"
$ns = Nodes (Dump 'c0')
$rm = $ns | Where-Object { $_.text -eq 'Remove' -or $_.desc -eq 'Remove' } | Select-Object -First 1
if ($rm) { "  Remove chip at [$($rm.x1),$($rm.y1)][$($rm.x2),$($rm.y2)]"; Tap $rm; Start-Sleep -Seconds 2 }
$ns = Nodes (Dump 'c1')
$st = $ns | Where-Object { $_.text -eq 'Stop' } | Select-Object -First 1
if ($st) { "  Stop at [$($st.x1),$($st.y1)][$($st.x2),$($st.y2)]"; Tap $st; Start-Sleep -Seconds 3 }
$ns = Nodes (Dump 'c2')
"  after: Steer=$($ns | ? { $_.text -eq 'Steer' }) Remove=$($ns | ? { $_.text -eq 'Remove' })"

"### A2.2 600 bar-off"
SetDisp '1366x600'
Start-Sleep -Seconds 2
Measure 'A2_600_BAROFF'

"### A2.3 768 bar-off"
SetDisp '1366x768'
Start-Sleep -Seconds 2
Measure 'A2_768_BAROFF'

"### A2.4 resize round-trip x2"
foreach ($round in 1..2) {
  SetDisp '1366x600'; Start-Sleep -Seconds 2; Measure "A2_rt${round}_600"
  SetDisp '1366x768'; Start-Sleep -Seconds 2; Measure "A2_rt${round}_768"
}

"### A2.5 restore 1366x768/160 explicitly"
SetDisp '1366x768'
& adbx shell wm density 160 | Out-Null
& adbx shell wm density
& adbx shell wm size

"### A2.6 logcat (app pid, -t)"
$appPid = (& adbx shell pidof $script:PKG 2>&1 | Out-String).Trim()
"  appPid=$appPid"
if ($appPid) {
  $lg = & adbx logcat -t 4000 -v brief 2>&1 | Out-String
  $mine = ($lg -split "`n") | Where-Object { $_ -match "\s$appPid\s" }
  $hits = $mine | Select-String -Pattern 'FATAL|IllegalArgumentException|maxHeight'
  "  app lines=$($mine.Count)"
  if ($hits) { "  !! MATCHES:"; $hits | ForEach-Object { "    $_" } } else { "  FATAL|IllegalArgumentException|maxHeight = EMPTY  (clean)" }
  $mine | Out-File -Encoding utf8 "$script:ART\A2_logcat_apppid.log"
} else { "  !! no app pid" }
"### done"
