$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

Dump-UI "lp0" | Out-Null
$row = $script:N | Where-Object { $_.GetAttribute('text') -like 'E2ECHK5556u1f*' } | Select-Object -First 1
if (-not $row) { Write-Output "ROW NOT FOUND"; exit 5 }
$b = $row.GetAttribute('bounds')
Write-Output "row bounds = $b"
if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { Write-Output "BAD"; exit 6 }
$cx = [int](([int]$Matches[1]+[int]$Matches[3])/2); $cy = [int](([int]$Matches[2]+[int]$Matches[4])/2)
Write-Output "long-press at $cx,$cy (1200ms)"

# long press via motionevent
A shell input motionevent DOWN $cx $cy | Out-Null
Start-Sleep -Milliseconds 1200
A shell input motionevent UP $cx $cy | Out-Null
Start-Sleep -Seconds 2

Dump-UI "lp1" | Out-Null
Write-Output "TOTAL: $($script:N.Count)"
foreach ($n in $script:N) {
  $d = $n.GetAttribute('content-desc')
  if (-not $d) { $d = $n.GetAttribute('text') }
  if ($d -and $d -match 'Delete|Rename|Cancel|Duplicate') {
    Write-Output ("  MENU [{0}] {1} clk={2} en={3}" -f $d, $n.GetAttribute('bounds'), $n.GetAttribute('clickable'), $n.GetAttribute('enabled'))
  }
}
Shot "E2ECHK5556u1f_longpress" | Out-Null
