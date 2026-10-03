# Inspect any overlay/dialog nodes that appeared after a long-press
param([Parameter(Mandatory=$true)][string]$Xml)
$ErrorActionPreference='Stop'
[xml]$d = Get-Content $Xml -Raw -Encoding UTF8
$KEYS = @('Delete','Remove','Cancel','Close','Copy','Edit','Steer','Send','Queued','Stop')
$hit = @()
foreach ($n in $d.SelectNodes('//node')) {
  $t = $n.GetAttribute('text'); $dsc = $n.GetAttribute('content-desc')
  $lbl = if ($t) { $t } else { $dsc }
  if (-not $lbl) { continue }
  foreach ($k in $KEYS) {
    if ($lbl -eq $k -or $lbl -like "*$k*") { $hit += [pscustomobject]@{ Lbl=$lbl; Bounds=$n.GetAttribute('bounds'); Clk=$n.GetAttribute('clickable') }; break }
  }
}
if ($hit.Count -eq 0) { Write-Output "  <none of the tracked labels present>" }
else { $hit | Sort-Object Bounds -Unique | Format-Table -AutoSize | Out-String | Write-Output }
