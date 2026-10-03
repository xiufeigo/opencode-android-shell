$ErrorActionPreference='Stop'
$ev="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98\5560_v98_drawer5"
function Nodes($tag){ $x=[xml](Get-Content "$ev\$tag.dump.xml" -Raw -Encoding UTF8); return $x.SelectNodes("//node") }
function B($n){ if($n.bounds -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]'){ return @([int]$Matches[1],[int]$Matches[2],[int]$Matches[3],[int]$Matches[4]) } ; return @(-1,-1,-1,-1) }
function Strip($nodes){
  # clickable nodes occupying the 1452..1548 band, ordered left->right
  $r = @()
  foreach($n in $nodes){ $b=B $n; if($n.clickable -eq 'true' -and $b[1] -eq 1452 -and $b[3] -eq 1548){ $r += ,@($b,$n) } }
  return $r
}

$g0 = Nodes "G0_baseline_closed"
$g1 = Nodes "G1_drawer_tap"
$s0 = Strip $g0
$s1 = Strip $g1

Write-Output "########## OPEN-STATE GEOMETRY ASSERTIONS (px) ##########"
Write-Output ""
Write-Output "--- (1)+(4) bottom strip, CLOSED vs OPEN (y must stay 1452..1548; defect signature 0..84) ---"
Write-Output ("  closed strip: {0} clickable nodes at y=1452..1548" -f $s0.Count)
Write-Output ("  open   strip: {0} clickable nodes at y=1452..1548" -f $s1.Count)
$max0=0; $max1=0
for($i=0;$i -lt [Math]::Max($s0.Count,$s1.Count);$i++){
  $a = if($i -lt $s0.Count){ "x={0}..{1}" -f $s0[$i][0][0],$s0[$i][0][2] } else {'-'}
  $b2 = if($i -lt $s1.Count){ "x={0}..{1}" -f $s1[$i][0][0],$s1[$i][0][2] } else {'-'}
  Write-Output ("    slot {0}  closed:{1,-14} open:{2}" -f $i,$a,$b2)
  if($i -lt $s0.Count -and $s0[$i][0][1] -gt $max0){$max0=$s0[$i][0][1]}
  if($i -lt $s1.Count -and $s1[$i][0][1] -gt $max1){$max1=$s1[$i][0][1]}
}
Write-Output ("  MAX strip top y:  closed={0}  open={1}   -> defect signature 0..84 present? {2}" -f $max0,$max1,$(if(($max0 -eq 0) -or ($max1 -eq 0)){'YES-REGRESSION'}else{'NO'}))

Write-Output ""
Write-Output "--- (2) NavHost / content container: must NOT be collapsed to zero ---"
foreach($pair in @(@('CLOSED',$g0),@('OPEN',$g1))){
  $best=$null; $bh=0
  foreach($n in $pair[1]){ if($n.class -match 'ComposeView|android.view.View'){ $b=B $n; $h=$b[3]-$b[1]; if($h -gt $bh){$bh=$h;$best=$b} } }
  Write-Output ("  {0,-7} largest view: {1}  height={2}px  -> {3}" -f $pair[0], $(if($best){"[$($best[0]),$($best[1])][$($best[2]),$($best[3])]"}else{'-'}), $bh, $(if($bh -gt 1000){'OK (not collapsed)'}else{'COLLAPSED!'}))
}

Write-Output ""
Write-Output "--- (2b) session content anchors (send / add buttons) ---"
foreach($pair in @(@('CLOSED',$g0),@('OPEN',$g1))){
  $c = $pair[1] | Where-Object { $_.class -eq 'android.view.View' -and $_.clickable -eq 'true' -and (B $_)[0] -gt 1000 -and (B $_)[1] -gt 1000 -and (B $_)[1] -lt 1400 } | Select-Object -First 1
  if($c){ $b=B $c; Write-Output ("  {0,-7} send-area button bounds=[{1},{2}][{3},{4}]  h={5} w={6}" -f $pair[0],$b[0],$b[1],$b[2],$b[3],($b[3]-$b[1]),($b[2]-$b[0])) }
  else { Write-Output ("  {0,-7} send-area button NOT FOUND" -f $pair[0]) }
}

Write-Output ""
Write-Output "--- (3) status bar band: must NOT be occluded (nodes with y2<=145) ---"
foreach($pair in @(@('CLOSED',$g0),@('OPEN',$g1))){
  $n = @($pair[1] | Where-Object { $b=B $_; $b[3] -le 145 -and $b[3] -gt 0 }).Count
  Write-Output ("  {0,-7} {1} node(s) in status-bar band -> {2}" -f $pair[0], $n, $(if($n -gt 0){'NOT occluded'}else{'OCCLUDED!'}))
}

Write-Output ""
Write-Output "--- (5) drawer-only rows visible in OPEN state (were absent in CLOSED) ---"
$rows = @()
foreach($n in $g1){ $t=$n.text; if($t){ $rows += $t } }
$newrows = @()
foreach($n in $g1){ $t=$n.text; if($t){ $in0 = $false; foreach($m in $g0){ if($m.text -eq $t){$in0=$true} }; if(-not $in0){ $newrows += $t } } }
Write-Output ("  node count: closed={0}  open={1}" -f $g0.Count, $g1.Count)
Write-Output ("  NEW text rows in OPEN: {0}" -f (($newrows | Select-Object -Unique) -join ' | '))
