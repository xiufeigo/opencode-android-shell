# Extract chip/row evidence from a dumped XML (writes to file, prints concise table)
param([Parameter(Mandatory=$true)][string]$Xml)
$ErrorActionPreference = 'Stop'
[xml]$d = Get-Content $Xml -Raw -Encoding UTF8

$WANT = @('Queue','Steer','Stop','Remove','×','Cancel','Save','Edit','Done')
$out = @()
foreach ($n in $d.SelectNodes('//node')) {
  $t = $n.GetAttribute('text'); $dsc = $n.GetAttribute('content-desc')
  $lbl = if ($t) { $t } else { $dsc }
  if (-not $lbl) { continue }
  if ($WANT -notcontains $lbl) { continue }
  $b = $n.GetAttribute('bounds')
  if ($b -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { continue }
  $out += [pscustomobject]@{
    Label   = $lbl
    Bounds  = $b
    Click   = $n.GetAttribute('clickable')
    Cls     = $n.GetAttribute('class').Split('.')[-1]
  }
}
if ($out.Count -eq 0) { Write-Output "  <no chips matched: $($WANT -join ',')>" }
else { $out | Format-Table -AutoSize | Out-String | Write-Output }
