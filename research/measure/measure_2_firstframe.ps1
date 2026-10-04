# measure_2_firstframe.ps1 — first-frame / cold-start baseline.
# Usage: pwsh -File research\measure\measure_2_firstframe.ps1
# 3 x `am start -W`; reports TotalTime / WaitTime.

. (Join-Path $PSScriptRoot 'common.ps1')

$rows = @()
for ($i=1; $i -le 3; $i++) {
  A shell am force-stop $Pkg
  Start-Sleep -Seconds 2
  $o = A shell am start -W -n $Act
  $o | Out-File -Encoding utf8 (Join-Path $OutDir ("amstart_$i.txt"))
  $t = $o -join "`n"
  Write-Output "===== run $i raw ====="
  Write-Output $t
  $num = { param($k) if ($t -match ("(?m)^\s*" + [regex]::Escape($k) + ":\s*(-?\d+)")) { [int]$Matches[1] } else { $null } }
  $rows += [pscustomobject]@{ run=$i; ThisTimeTotal=(& $num 'ThisTimeTotal'); TotalTime=(& $num 'TotalTime'); WaitTime=(& $num 'WaitTime') }
  Start-Sleep -Seconds 2
}

Write-Output "===== summary ====="
$rows | Format-Table -AutoSize | Out-String | Write-Output
$rows | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir 'firstframe.json')
Write-Output ("TotalTime values: " + (($rows | ForEach-Object { $_.TotalTime }) -join ', '))
