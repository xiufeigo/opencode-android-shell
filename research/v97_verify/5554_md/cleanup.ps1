# Clear queued rows created by this test round (prefix E2ECHK5554u1t)
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"

# cancel any open edit loan first (IME down, then Esc) so row actions are live
$x = Dump 'c0'; $ns = Nodes $x
$steerChip = @($ns | Where-Object { $_.text -eq 'Steer' -and $_.clickable -eq 'false' }).Count
if ($steerChip -gt 0) {
  Write-Output "cancelling open edit loan..."
  adbx shell input keyevent 111 | Out-Null; Start-Sleep -Milliseconds 900
  $x = Dump 'c1'; $ns = Nodes $x
  if (@($ns | Where-Object { $_.text -eq 'Steer' -and $_.clickable -eq 'false' }).Count -gt 0) {
    adbx shell input keyevent 111 | Out-Null; Start-Sleep -Milliseconds 1200
  }
}

for ($i = 0; $i -lt 14; $i++) {
  $x = Dump "cl$i"; $ns = Nodes $x
  $labels = @($ns | Where-Object { $_.text -eq 'Queued' })
  $rms    = @($ns | Where-Object { $_.text -eq 'Remove' -and $_.clickable -eq 'true' })
  if ($labels.Count -eq 0) { Write-Output "no queued rows left (iter $i)"; break }
  if ($rms.Count -eq 0)    { Write-Output "queued rows present ($($labels.Count)) but NO Remove button in a11y tree (cropped) - stopping"; break }

  # remove the BOTTOM-most reachable row so the ones above stay stable
  $r = $rms | Sort-Object { $_.y1 } | Select-Object -Last 1
  Write-Output "iter $i : Queued=$($labels.Count) Remove=$($rms.Count) -> tap Remove at y=$($r.y1)"
  Tap $r
  Start-Sleep -Milliseconds 1800
}

$x = Dump 'cl_final'; $ns = Nodes $x
Write-Output ""
Write-Output "FINAL queued labels : $(@($ns | Where-Object { $_.text -eq 'Queued' }).Count)"
Write-Output "FINAL my nodes still in a11y:"
$ns | Where-Object { $_.text -like '*E2ECHK5554u1t*' } | ForEach-Object { "  '$($_.text)' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)]" }
Shot 'cleanup_final' | Out-Null
