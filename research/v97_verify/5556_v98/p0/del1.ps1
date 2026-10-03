$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

Dump-UI "z1" | Out-Null
Write-Output "--- menu nodes ---"
foreach ($n in $script:N) {
  $d = $n.GetAttribute('content-desc')
  if (-not $d) { $d = $n.GetAttribute('text') }
  if ($d -and $d -match 'Delete|Rename|Cancel') {
    Write-Output ("  [{0}] {1} clk={2} en={3}" -f $d, $n.GetAttribute('bounds'), $n.GetAttribute('clickable'), $n.GetAttribute('enabled'))
  }
}
$del = $script:N | Where-Object { $_.GetAttribute('content-desc') -eq 'Delete' } | Select-Object -First 1
if (-not $del) { Write-Output "NO DELETE"; exit 5 }
$b = $del.GetAttribute('bounds')
if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
  $cx = [int](([int]$Matches[1]+[int]$Matches[3])/2); $cy = [int](([int]$Matches[2]+[int]$Matches[4])/2)
  Write-Output "tap Delete @ $cx,$cy"
  A shell input tap $cx $cy | Out-Null
  Start-Sleep -Seconds 2
}
Dump-UI "z2" | Out-Null
Write-Output "--- dialog nodes ---"
foreach ($n in $script:N) {
  $d = $n.GetAttribute('content-desc')
  if (-not $d) { $d = $n.GetAttribute('text') }
  if ($d -and $d -match 'Delete|Cancel|confirm|Are you') {
    Write-Output ("  [{0}] {1} clk={2} en={3}" -f $d, $n.GetAttribute('bounds'), $n.GetAttribute('clickable'), $n.GetAttribute('enabled'))
  }
}
Shot "E2ECHK5556u1f_confirm" | Out-Null
Write-Output "pid: $(Get-Pid)  logcat: $(Test-CrashLog)"
