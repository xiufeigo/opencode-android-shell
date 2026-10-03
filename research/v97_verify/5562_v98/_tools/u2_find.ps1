# find nodes whose text/content-desc matches a pattern; print center coords
param([Parameter(Mandatory=$true)][string]$Xml,[Parameter(Mandatory=$true)][string]$Pattern)
$x = Get-Content $Xml -Raw
# capture node elements
$nodes = [regex]::Matches($x, '<node\b[^>]*>')
foreach($n in $nodes){
  $s = $n.Value
  $t = $null
  foreach($attr in 'text','content-desc'){
    $m = [regex]::Match($s, "$attr=`"([^`"]*)`"")
    if($m.Success -and $m.Groups[1].Value.Trim()){ $t = $m.Groups[1].Value; break }
    $m2 = [regex]::Match($s, "$attr='([^']*)'")
    if($m2.Success -and $m2.Groups[1].Value.Trim()){ $t = $m2.Groups[1].Value; break }
  }
  if($null -eq $t){ continue }
  if($t -notmatch $Pattern){ continue }
  $b = [regex]::Match($s,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if(-not $b.Success){ continue }
  $x1=[int]$b.Groups[1].Value; $y1=[int]$b.Groups[2].Value; $x2=[int]$b.Groups[3].Value; $y2=[int]$b.Groups[4].Value
  $cx=[int](($x1+$x2)/2); $cy=[int](($y1+$y2)/2)
  "$t`t[$x1,$y1][$x2,$y2]`tcenter=$cx,$cy"
}
