param([string]$Path)
$c = Get-Content $Path -Raw
$m = [regex]::Matches($c, '<node[^>]*>')
foreach ($n in $m) {
  $s = $n.Value
  $t = ''
  if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
  $cd = ''
  if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
  $cl = ''
  if ($s -match 'class="([^"]*)"') { $cl = $Matches[1] }
  $b = ''
  if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') { $b = "$($Matches[1]),$($Matches[2])-$($Matches[3]),$($Matches[4])" }
  $label = $t
  if ($label.Trim() -eq '') { $label = "[cd]$cd" }
  if ($label.Trim() -eq '') { continue }
  $short = $cl -replace 'android\.widget\.','' -replace 'android\.view\.',''
  "{0,-42} {1,-20} {2}" -f $label.Substring(0, [Math]::Min(42, $label.Length)), $short, $b
}
