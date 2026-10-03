$ErrorActionPreference = 'Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\p0\probe.ps1"

function TryLongPress([int]$x, [int]$y, [string]$label) {
  A shell input motionevent DOWN $x $y | Out-Null
  Start-Sleep -Milliseconds 1300
  A shell input motionevent UP $x $y | Out-Null
  Start-Sleep -Seconds 2
  Dump-UI "lpm" | Out-Null
  $found = $false
  foreach ($n in $script:N) {
    $d = $n.GetAttribute('content-desc')
    if (-not $d) { $d = $n.GetAttribute('text') }
    if ($d -and $d -match 'Delete|Rename') {
      Write-Output ("  MENU[{0}] [{1}] {2} clk={3} en={4}" -f $label, $d, $n.GetAttribute('bounds'), $n.GetAttribute('clickable'), $n.GetAttribute('enabled'))
      $found = $true
    }
  }
  if (-not $found) { Write-Output "  NO-MENU[$label] @ $x,$y (nodes=$($script:N.Count))" }
  return $found
}

# re-open drawer (previous tap may have closed it)
Dump-UI "dr0" | Out-Null
$has = $script:N | Where-Object { $_.GetAttribute('content-desc') -eq 'Close navigation menu' }
if (-not $has) {
  Write-Output (TapDesc "Open sessions")
  Start-Sleep -Seconds 2
  Dump-UI "dr1" | Out-Null
}
# find the drawer row: the E2ECHK text that has a "just now"-style sibling, use x further right
$rows = @($script:N | Where-Object { $_.GetAttribute('text') -like 'E2ECHK5556u1f*' })
Write-Output "E2ECHK text nodes: $($rows.Count)"
foreach ($r in $rows) { Write-Output "  '$($r.GetAttribute('text'))' @ $($r.GetAttribute('bounds'))" }

foreach ($pt in @(@(500,300), @(400,300), @(600,300))) {
  $ok = TryLongPress $pt[0] $pt[1] ("$($pt[0]),$($pt[1])")
  if ($ok) { break }
}
Shot "E2ECHK5556u1f_menu" | Out-Null
