# v140: find a node by literal text/desc inside a dump and report its bounds + center
param(
  [string]$Xml,
  [string]$Needle,
  [string]$Attr = 'content-desc'
)
$x = Get-Content $Xml -Raw
$hits = [regex]::Matches($x, '<node\s[^>]*' + [regex]::Escape($Needle) + '[^>]*>')
"matches for '$Needle' ($Attr): $($hits.Count)"
foreach($h in $hits){
  $v = $h.Value
  $d  = if($v -match 'content-desc="([^"]*)"'){$Matches[1]}else{''}
  $t  = if($v -match 'text="([^"]*)"'){$Matches[1]}else{''}
  $c  = if($v -match 'class="([^"]*)"'){$Matches[1]}else{''}
  $ck = if($v -match 'clickable="([^"]*)"'){$Matches[1]}else{'false'}
  $b  = if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){$Matches[1]+','+$Matches[2]+']['+$Matches[3]+','+$Matches[4]}else{''}
  $cx = if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int](([int]$Matches[1]+[int]$Matches[3])/2)}else{0}
  $cy = if($v -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'){[int](([int]$Matches[2]+[int]$Matches[4])/2)}else{0}
  "  cls=$c clk=$ck bounds=[$b] center=($cx,$cy) desc='$d' text='$t'"
}
