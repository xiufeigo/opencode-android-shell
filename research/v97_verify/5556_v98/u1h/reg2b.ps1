# u1h regression 2b: More options menu evidence + attempt to build a real queue
. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1h\helper.ps1

# --- More options evidence ---
Assert-Focus "R2-more-pre" | Out-Null
$mo = (Get-Nodes (Dump "q01_pre_more")) | Where-Object { $_.'content-desc' -eq 'More options' } | Select-Object -First 1
$b = Bnd $mo.bounds
A shell input tap $b.Cx $b.Cy | Out-Null
Start-Sleep -Seconds 3
Assert-Focus "R2-more-open" | Out-Null
$pm = Dump "q02_more_open"
Write-Output "=== More options menu items ==="
$pk = (Get-Nodes $pm) | Where-Object { $_.package -eq $pkg } | Select-Object -First 1
Write-Output "overlay package: $($pk.package)"
(Get-Nodes $pm) | Where-Object { ($_.text -ne '' -or $_.'content-desc' -ne '') -and $_.bounds -match '^\[(\d+),(\d+)\]' } | ForEach-Object {
  $bb = Bnd $_.bounds
  "  {0,-22} {1,-28} t='{2}' d='{3}'" -f $_.class, $_.bounds, $_.text, $_.'content-desc'
}
# close via scrim tap (NO ESC per discipline)
A shell input tap 60 700 | Out-Null
Start-Sleep -Seconds 2
Assert-Focus "R2-more-closed" | Out-Null
Write-Output "closed by scrim tap (no ESC, no BACK)"
