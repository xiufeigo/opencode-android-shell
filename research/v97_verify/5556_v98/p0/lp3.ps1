$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

# drawer row for my session is [24,179][203,196]; press on the row band at y=250 (row center zone)
# First: re-dump to confirm drawer is open
Dump-UI "z0" | Out-Null
$nav = $script:N | Where-Object { $_.GetAttribute('content-desc') -eq 'Close navigation menu' }
Write-Output "drawer open: $([bool]$nav)"

foreach ($pt in @(@(150,187), @(150,215), @(150,250))) {
  A shell input motionevent DOWN $pt[0] $pt[1] | Out-Null
  Start-Sleep -Milliseconds 1300
  A shell input motionevent UP $pt[0] $pt[1] | Out-Null
  Start-Sleep -Seconds 2
  Dump-UI "zm" | Out-Null
  $hit = $false
  foreach ($n in $script:N) {
    $d = $n.GetAttribute('content-desc')
    if (-not $d) { $d = $n.GetAttribute('text') }
    if ($d -and $d -match 'Delete|Rename') {
      Write-Output ("  MENU @($($pt[0]),$($pt[1])) [{0}] {1} clk={2} en={3}" -f $d, $n.GetAttribute('bounds'), $n.GetAttribute('clickable'), $n.GetAttribute('enabled'))
      $hit = $true
    }
  }
  if ($hit) { Write-Output "GOT MENU"; break }
  Write-Output "  no menu @($($pt[0]),$($pt[1])) nodes=$($script:N.Count)"
}
Shot "E2ECHK5556u1f_menu2" | Out-Null
