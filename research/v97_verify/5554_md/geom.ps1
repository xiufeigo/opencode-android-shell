# Geometry check: pane box / timeline box / readable / criterion ①(i) ②
# Method per REPORT_BASELINE_RECHECK.md §1:
#   unionBottom  = union of composer children bottoms (from editor top edge, excluding 'Open terminal')
#   paneBottom   = production pane writable-area bottom (dump side: pane host box y2)
#   ①(i)         = unionBottom - paneBottom   (768: 340-350 = -10 ; 600: 249-261 = -12)
#   ②            = unionPx <= band(48)
#   timeline box = paneH - 48 ; readable = paneH - 96 (768) / paneH - 48 (600)
param([Parameter(Mandatory=$true)][string]$Tag, [switch]$Short)
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"

$ime = AssertIme "reading geometry ($Tag)"
$x = Dump $Tag; $ns = Nodes $x
Shot $Tag | Out-Null
$screenW = 1366

$et = $ns | Where-Object { $_.cls -eq 'EditText' } | Select-Object -First 1
if (-not $et) { Write-Output "NO EditText - abort"; exit 1 }
Write-Output "editor: [$($et.x1),$($et.y1)][$($et.x2),$($et.y2)] h=$($et.h)"

# composer children: at/below the editor top edge, inside the composer card only
# (excludes 'Open terminal' and any system bar / nav strip that sits far below)
$kids = $ns | Where-Object {
  $_.y1 -ge ($et.y1 - 2) -and $_.y2 -le ($et.y2 + 60) -and
  -not ($_.text -eq 'Open terminal' -or $_.desc -eq 'Open terminal')
}
$ux1 = ($kids | Measure-Object x1 -Minimum).Minimum
$uy1 = ($kids | Measure-Object y1 -Minimum).Minimum
$ux2 = ($kids | Measure-Object x2 -Maximum).Maximum
$uy2 = ($kids | Measure-Object y2 -Maximum).Maximum
$unionPx = $uy2 - $uy1
Write-Output "composer children:"
$kids | ForEach-Object { $l = if($_.text){$_.text}else{$_.desc}; "  [$($_.cls)] '$l' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] h=$($_.h) clk=$($_.clickable)" }
Write-Output "union = [$ux1,$uy1][$ux2,$uy2]  unionPx=$unionPx  unionBottom=$uy2"

# pane host: full-width container that starts above the editor and reaches past the union bottom
$hosts = $ns | Where-Object {
  $_.x1 -le 2 -and $_.x2 -ge ($screenW - 2) -and $_.y1 -le $et.y1 -and $_.y2 -ge ($uy2 - 2) -and $_.h -lt 900 -and $_.h -ge 40
}
$paneHost = $hosts | Sort-Object { $_.h } | Select-Object -First 1
if (-not $paneHost) { Write-Output "NO pane host found"; Write-Output "candidates:"; $hosts | ForEach-Object { "  [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] h=$($_.h)" }; exit 1 }
$paneH = $paneHost.h; $paneBottom = $paneHost.y2
Write-Output "pane host: [$($paneHost.x1),$($paneHost.y1)][$($paneHost.x2),$($paneHost.y2)] h=$paneH  paneBottom=$paneBottom"

$c1 = $uy2 - $paneBottom
$band = 48
$timelineBox = $paneH - $band
$readable = if ($paneH -ge 150) { $paneH - 96 } else { $paneH - 48 }
$cond2 = ($unionPx -le $band)

Write-Output ""
Write-Output "===== GEOMETRY @ $Tag ====="
Write-Output "  pane h              = $paneH"
Write-Output "  timeline box        = $timelineBox   (paneH-48)"
Write-Output "  readable            = $readable"
Write-Output "  ①(i) unionBottom-paneBottom = $c1"
Write-Output "  ② unionPx<=48       = $cond2  (unionPx=$unionPx)"
Write-Output "  mInputShown         = $ime"

# queue-row action buttons (Steer / Remove) while the bar is on screen
$qchips = $ns | Where-Object { ($_.text -eq 'Steer' -or $_.text -eq 'Remove') -and $_.clickable -eq 'true' }
if ($qchips) {
  Write-Output "  queue actions (bar on screen):"
  $qchips | ForEach-Object { $l = if($_.text){$_.text}else{$_.desc}; "    '$l' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)] h=$($_.h) w=$($_.w) clk=$($_.clickable)" }
}
Write-Output "======================="

