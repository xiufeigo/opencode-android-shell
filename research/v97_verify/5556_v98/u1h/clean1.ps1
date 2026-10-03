# u1h cleanup: stop turn, delete own session, wm reset, sdcard check
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1h\helper.ps1

# --- 1. stop the turn ---
Assert-Focus "cl-stop" | Out-Null
$stp = (Get-Nodes (Dump "c01_pre_stop")) | Where-Object { $_.'content-desc' -eq 'Stop' } | Select-Object -First 1
if ($stp) {
  $b = Bnd $stp.bounds
  Write-Output "tapping Stop at $($stp.bounds)"
  A shell input tap $b.Cx $b.Cy | Out-Null
  Start-Sleep -Seconds 4
} else { Write-Output "no Stop button (turn already idle)" }
$after = Get-Nodes (Dump "c02_after_stop")
$btn = ($after | Where-Object { $_.'content-desc' -eq 'Send' -or $_.'content-desc' -eq 'Stop' } | Select-Object -First 1)
Write-Output "send-slot now = '$($btn.'content-desc')' at $($btn.bounds)"
Shot "c02_after_stop"

# --- 2. open sessions drawer ---
Assert-Focus "cl-drawer" | Out-Null
$os = (Get-Nodes (Dump "c03_pre_drawer")) | Where-Object { $_.'content-desc' -eq 'Open sessions' } | Select-Object -First 1
$b = Bnd $os.bounds
A shell input tap $b.Cx $b.Cy | Out-Null
Start-Sleep -Seconds 4
Assert-Focus "cl-drawer-open" | Out-Null
$rows = Get-Nodes (Dump "c04_drawer")
Write-Output "=== drawer rows (u1h / u1f only shown) ==="
$rows | Where-Object { $_.'content-desc' -ne '' -or $_.text -ne '' } | ForEach-Object {
  $lbl = if ($_.text -ne '') { $_.text } else { $_.'content-desc' }
  if ($lbl -match 'u1h|u1f|Open sessions|Home') { "  {0,-22} {1,-28} t='{2}' d='{3}'" -f $_.class, $_.bounds, $_.text, $_.'content-desc' }
}
Write-Output "--- full row list (bounds + label) ---"
$rows | Where-Object { $_.clickable -eq 'true' -and $_.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]' } | ForEach-Object {
  $bb = Bnd $_.bounds
  if ($bb.W -gt 100 -and $bb.H -gt 20 -and $bb.T -gt 60) { "  ROW {0,-24} t='{1}' d='{2}'" -f $_.bounds, $_.text, $_.'content-desc' }
}
