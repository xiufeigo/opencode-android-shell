param(
  [Parameter(Mandatory=$true)][string]$File,
  [Parameter(Mandatory=$true)][string]$Pattern,
  [int]$Radius = 320,
  [int]$MaxHits = 12
)
$ErrorActionPreference = 'Stop'
$text = [System.IO.File]::ReadAllText($File)
$rx = [regex]::new($Pattern)
$ms = $rx.Matches($text)
Write-Output "=== $File  pattern=[$Pattern]  hits=$($ms.Count) ==="
$i = 0
foreach ($m in $ms) {
  if ($i -ge $MaxHits) { Write-Output "--- (truncated at $MaxHits hits) ---"; break }
  $i++
  $s = [Math]::Max(0, $m.Index - $Radius)
  $len = [Math]::Min($text.Length - $s, $Radius * 2)
  $snip = $text.Substring($s, $len)
  Write-Output ""
  Write-Output ("--- hit #{0} @offset {1} ---" -f $i, $m.Index)
  Write-Output $snip
}