# Clear queued rows: scroll the bar so row actions enter the a11y tree, then Remove bottom-up
$W = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md'
. "$W\helper.ps1"

function RowsVisible($ns) {
  $lbl = @($ns | Where-Object { $_.text -eq 'Queued' })
  $out = @()
  foreach ($l in $lbl) {
    $c = $ns | Where-Object { $_.text -and $_.text -like 'E2ECHK5554u1t*' -and $_.w -lt 500 -and
      ([math]::Abs($_.cy - $l.cy) -le 12) -and ($_.x1 -gt $l.x2) } | Select-Object -First 1
    if ($c) { $out += [pscustomobject]@{ Text=$c.text; Cy=$c.cy } }
  }
  return $out
}

# 0) make sure no edit loan is open
$x = Dump 's0'; $ns = Nodes $x
if (@($ns | Where-Object { $_.text -eq 'Steer' -and $_.clickable -eq 'false' }).Count -gt 0) {
  Write-Output "cancel edit loan..."
  adbx shell input keyevent 111 | Out-Null; Start-Sleep -Milliseconds 900
  $x = Dump 's1'; $ns = Nodes $x
  if (@($ns | Where-Object { $_.text -eq 'Steer' -and $_.clickable -eq 'false' }).Count -gt 0) {
    adbx shell input keyevent 111 | Out-Null; Start-Sleep -Milliseconds 1200
  }
}

for ($i = 0; $i -lt 20; $i++) {
  $x = Dump "s_$i"; $ns = Nodes $x
  $rows = RowsVisible $ns
  $rms   = @($ns | Where-Object { $_.text -eq 'Remove' -and $_.clickable -eq 'true' })
  Write-Output "iter $i : visibleQueuedRows=$($rows.Count) RemoveButtons=$($rms.Count) [$($rows.Text -join ' | ')]"
  if ($rows.Count -eq 0) { Write-Output "QUEUE EMPTY - done"; break }

  if ($rms.Count -gt 0) {
    # bottom-most Remove -> it belongs to the bottom-most row
    $r = $rms | Sort-Object { $_.y1 } | Select-Object -Last 1
    Write-Output "   -> Remove at [$($r.x1),$($r.y1)][$($r.x2),$($r.y2)]"
    Tap $r
    Start-Sleep -Milliseconds 1700
  } else {
    # scroll the bar to the very top so the parked-first row's actions render
    Write-Output "   -> no Remove in tree; scrolling bar to top"
    adbx shell input swipe 680 500 680 720 400 | Out-Null
    Start-Sleep -Milliseconds 1500
  }
}

$x = Dump 's_final'; $ns = Nodes $x
Write-Output ""
Write-Output "FINAL Queued labels : $(@($ns | Where-Object { $_.text -eq 'Queued' }).Count)"
Write-Output "FINAL my a11y nodes:"
$ns | Where-Object { $_.text -like '*E2ECHK5554u1t*' } | ForEach-Object { "  '$($_.text)' [$($_.x1),$($_.y1)][$($_.x2),$($_.y2)]" }
Shot 'cleanup_final2' | Out-Null
