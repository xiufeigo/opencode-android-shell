$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

# open the sessions drawer to find the self-created session
Dump-UI "d0" | Out-Null
Write-Output (TapDesc "Open sessions")
Start-Sleep -Seconds 2
Dump-UI "d1" | Out-Null
Write-Output "TOTAL: $($script:N.Count)"
$i = 0
foreach ($n in $script:N) {
  $i++
  $d = $n.GetAttribute('content-desc')
  if (-not $d) { $d = $n.GetAttribute('text') }
  if ($d -and $d.Length -gt 60) { $d = $d.Substring(0,60) + '~' }
  Write-Output ("{0,3} | {1} | {2} | clk={3} en={4}" -f $i, ($n.GetAttribute('class') -replace 'android\.widget\.',''), $d, $n.GetAttribute('clickable'), $n.GetAttribute('enabled'))
}
