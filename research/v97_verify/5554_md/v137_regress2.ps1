# v137 Task A2 (retry): re-focus the editor after every resize so mInputShown is true.
$ErrorActionPreference = 'Continue'
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"

function FocusEditor {
  $ns = Nodes (Dump 'f0')
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not $et) { return $false }
  Tap $et
  Start-Sleep -Milliseconds 1200
  return $true
}

function Measure2 {
  param([string]$Tag)
  # bring the keyboard up and make sure it is really shown
  for ($try = 1; $try -le 3; $try++) {
    $ime = ImeState
    if ($ime) { break }
    FocusEditor | Out-Null
    Start-Sleep -Milliseconds 800
  }
  $ime = ImeState
  $x = Dump $Tag; $ns = Nodes $x
  Shot $Tag | Out-Null
  $et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
  if (-not $et) { Write-Output "  $Tag : NO EditText (mInputShown=$ime)"; return }
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
  if (-not $pane) { Write-Output "  $Tag : NO pane host (mInputShown=$ime)"; return }
  $paneH = $pane.h
  $timelineBox = $paneH - 48
  $readable = if ($paneH -ge 150) { $paneH - 96 } else { $paneH - 48 }
  $c1 = $uy2 - $pane.y2
  $q = $ns | Where-Object { $_.text -eq 'Steer' -or $_.text -eq 'Remove' } | Select-Object -First 1
  $form = if ($q) { 'BAR-ON ' } else { 'BAR-OFF' }
  $btn = $ns | Where-Object { ($_.text -eq 'Add' -or $_.text -eq 'Stop' -or $_.text -eq 'Send') -and $_.h -eq 48 } | Select-Object -First 1
  $bt = if ($btn) { $btn.y1 } else { '-' }
  Write-Output ("  {0,-16} {1} paneH={2,-4} timelineBox={3,-4} readable={4,-4} unionPx={5,-3} unionBottom={6,-4} paneBottom={7,-4} (i)={8,-3} etTop={9,-4} btnTop={10,-4} topDelta={11,-3} mInputShown={12}" -f `
    $Tag, $form, $paneH, $timelineBox, $readable, $unionPx, $uy2, $pane.y2, $c1, $et.y1, $bt, $(if($btn){$et.y1 - $btn.y1}else{'n/a'}), $ime)
}

function SetDisp { param([string]$sz)
  adbx shell wm size $sz | Out-Null
  Start-Sleep -Seconds 3
}

"### 600 bar-off"
SetDisp '1366x600'
Measure2 'A2_600_BAROFF'

"### 768 bar-off"
SetDisp '1366x768'
Measure2 'A2_768_BAROFF'

"### resize round-trip x2 (each leg re-measured)"
foreach ($r in 1..2) {
  SetDisp '1366x600'; Measure2 "RT${r}_600"
  SetDisp '1366x768'; Measure2 "RT${r}_768"
}

"### restore explicit 1366x768 / 160"
& adbx shell wm size 1366x768 | Out-Null
& adbx shell wm density 160 | Out-Null
Start-Sleep -Seconds 2
& adbx shell wm size
& adbx shell wm density
"### done"
