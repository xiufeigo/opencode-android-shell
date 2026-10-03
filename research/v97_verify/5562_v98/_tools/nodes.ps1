param([Parameter(Mandatory=$true)][string]$Path)
$x = Get-Content $Path -Raw
$m=[regex]::Matches($x,'<node[^>]*/?>')
foreach($n in $m){
  $s=$n.Value
  $t=[regex]::Match($s,'text="([^"]*)"').Groups[1].Value
  $c=[regex]::Match($s,'content-desc="([^"]*)"').Groups[1].Value
  $b=[regex]::Match($s,'bounds="([^"]*)"').Groups[1].Value
  $cl=[regex]::Match($s,'clickable="([^"]*)"').Groups[1].Value
  $en=[regex]::Match($s,'enabled="([^"]*)"').Groups[1].Value
  $pk=[regex]::Match($s,'package="([^"]*)"').Groups[1].Value
  if($t -ne "" -or $c -ne ""){ "T[$t] C[$c] B[$b] click=$cl en=$en pkg=$pk" }
}
