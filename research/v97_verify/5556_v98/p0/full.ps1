$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"
Dump-UI "qfull" | Out-Null
Write-Output "TOTAL: $($script:N.Count)"
$i = 0
foreach ($n in $script:N) {
  $i++
  $d = $n.GetAttribute('content-desc')
  if (-not $d) { $d = $n.GetAttribute('text') }
  if ($d -and $d.Length -gt 45) { $d = $d.Substring(0,45) + '~' }
  Write-Output ("{0,3} | {1} | {2} | {3} | clk={4}" -f $i, ($n.GetAttribute('class') -replace 'android\.widget\.',''), $d, $n.GetAttribute('bounds'), $n.GetAttribute('clickable'))
}
