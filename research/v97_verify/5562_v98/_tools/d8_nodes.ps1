param(
  [Parameter(Mandatory=$true)][string]$Path,
  [string]$Filter = ""
)
# D9-safe: match BOTH text="..." and text='...' (uiautomator may emit either)
$x = Get-Content $Path -Raw
foreach($n in [regex]::Matches($x,'<node[^>]*?>')){
  $s=$n.Value
  $tm=[regex]::Match($s,'text="([^"]*)"')
  if(-not $tm.Success){ $tm=[regex]::Match($s,"text='([^']*)'") }
  $t=$tm.Groups[1].Value
  $cm=[regex]::Match($s,'content-desc="([^"]*)"')
  if(-not $cm.Success){ $cm=[regex]::Match($s,"content-desc='([^']*)'") }
  $c=$cm.Groups[1].Value
  $b=[regex]::Match($s,'bounds="([^"]*)"').Groups[1].Value
  $f=[regex]::Match($s,'focused="([^"]*)"').Groups[1].Value
  $cl=[regex]::Match($s,'clickable="([^"]*)"').Groups[1].Value
  $pk=[regex]::Match($s,'package="([^"]*)"').Groups[1].Value
  $rid=[regex]::Match($s,'resource-id="([^"]*)"').Groups[1].Value
  $line = "T[$t] C[$c] B[$b] foc=$f click=$cl"
  if($Filter -eq ""){ if($t -ne "" -or $c -ne ""){ $line } }
  elseif($t -like "*$Filter*" -or $c -like "*$Filter*" -or $rid -like "*$Filter*"){ $line }
}
