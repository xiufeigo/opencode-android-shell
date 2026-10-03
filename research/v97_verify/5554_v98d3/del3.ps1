# del3.ps1 - D3-3: delete a row by NAME. Scrolls until the row's ⋮ is reachable, arms it,
# records the confirm-bar y-range vs the panel viewport, confirms, verifies which row died.
#
# Discipline: no BACK(4), no ESC(111), coords parsed at runtime, screenshots via screencap -p + pull.
param([string]$Target,[int]$MaxScroll=14)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\lib.ps1"

function SshRowNodes($x){
  # SSH rows are the row-title TextViews. Match our own prefix to stay off other rounds' rows.
  return @($x.SelectNodes("//node") | Where-Object { $_.text -like "E2ECHK5554d3c*" -or $_.text -eq "127.0.0.1:44521" } |
           Sort-Object { (BNum $_.bounds)[1] })
}
function RowList($x){
  $out=@()
  foreach($r in (SshRowNodes $x)){ $n=BNum $r.bounds; $out += ("{0}@y{1}" -f $r.text,$n[1]) }
  return ($out -join " ; ")
}

# ---- find the ⋮ belonging to $Target: the "Server actions" node just BELOW that row's title
function FindOverflow($x,[string]$target){
  $row=@($x.SelectNodes("//node")|Where-Object{$_.text -eq $target})[0]
  if(-not $row){ return $null }
  $ry=(BNum $row.bounds)[3]
  $acts=@($x.SelectNodes("//node")|Where-Object{$_."content-desc" -eq "Server actions"} |
          ForEach-Object{ [pscustomobject]@{b=$_.bounds; y=(BNum $_.bounds)[1]} } | Where-Object{$_.y -ge $ry} |
          Sort-Object y)
  if($acts.Count -eq 0){ return $null }
  return $acts[0].b
}

$x=Cap "D3_${Target}_00_start"
if(-not (InServers $x)){ Write-Output "NOT_IN_SERVERS"; exit 2 }
$vp=PanelViewport $x
Write-Output "VIEWPORT=$vp  vpY=[$(BNum $vp)[1],$(BNum $vp)[3]]"
Write-Output "ROWS_BEFORE: $(RowList $x)"

# ---- scroll until the target row is visible with a usable ⋮ inside the viewport
$ob=FindOverflow $x $Target
$scrolled=0
while(-not $ob -and $scrolled -lt $MaxScroll){
  $vpN=BNum $vp
  $swY=[int](($vpN[1]+$vpN[3])/2)
  & $global:ADB -s $global:DEV shell input swipe 540 $swY 540 ([int]($swY-500)) 250
  Start-Sleep -Milliseconds 1100
  $scrolled++
  $x=Cap "D3_${Target}_01_s${scrolled}"
  $ob=FindOverflow $x $Target
}
if(-not $ob){ Write-Output "OVERFLOW_NOT_FOUND after $scrolled scrolls"; exit 3 }
$vp=PanelViewport $x
Write-Output "SCROLLS=$scrolled  OVERFLOW=$ob  VIEWPORT=$vp vpY=[$(BNum $vp)[1],$(BNum $vp)[3]]"

# row title y BEFORE the arm (to prove the list scrolled)
$rowBefore=@($x.SelectNodes("//node")|Where-Object{$_.text -eq $Target})[0]
Write-Output "ROW_Y_BEFORE=$((BNum $rowBefore.bounds)[1])"

# ---- arm
$oc=Ctr $ob; TapXY $oc[0] $oc[1] 1600
$m=Cap "D3_${Target}_02_menu"
$del=@(Find $m "Delete")[0]
if(-not $del){ Write-Output "NO_DELETE_MENU_ITEM"; exit 4 }
$dc=Ctr $del.bounds
Write-Output "MENU_DELETE=$($del.bounds) tap=$($dc[0]),$($dc[1])"
TapXY $dc[0] $dc[1] 2200

# ---- the critical reading: confirm bar geometry
$b=Cap "D3_${Target}_03_bar"
$vp=PanelViewport $b
$vpN=BNum $vp
$title=@(Find $b "Delete SSH server?")[0]
if(-not $title){ Write-Output "BAR_TITLE_NOT_IN_A11Y_TREE (the D3 failure signature)"; }
else{
  $tn=BNum $title.bounds
  $canc=@(Find $b "Cancel")
  $delb=@($b.SelectNodes("//node")|Where-Object{$_.text -eq "Delete"})
  $minY=$tn[1]; $maxY=$tn[3]
  foreach($c in $canc){ $cn=BNum $c.bounds; if($cn[1] -lt $minY){$minY=$cn[1]}; if($cn[3] -gt $maxY){$maxY=$cn[3]} }
  foreach($c in $delb){ $cn=BNum $c.bounds; if($cn[1] -lt $minY){$minY=$cn[1]}; if($cn[3] -gt $maxY){$maxY=$cn[3]} }
  Write-Output "BAR_TITLE=$($title.bounds)"
  $canc | ForEach-Object { Write-Output "BAR_CANCEL=$($_.bounds)" }
  $delb | ForEach-Object { Write-Output "BAR_DELETE=$($_.bounds)" }
  Write-Output "CONFIRM_BAR_Y=[$minY,$maxY]"
  Write-Output "VIEWPORT_BOUNDS=$vp  VIEWPORT_Y=[$($vpN[1]),$($vpN[3])]"
  if($minY -ge $vpN[1] -and $maxY -le $vpN[3]){ Write-Output "GEOMETRY=PASS (bar fully inside viewport)" }
  else { Write-Output "GEOMETRY=FAIL (bar outside viewport)" }
  $rowAfter=@($b.SelectNodes("//node")|Where-Object{$_.text -eq $Target})[0]
  if($rowAfter){ Write-Output "ROW_Y_AFTER=$((BNum $rowAfter.bounds)[1])" }
  # confirm
  $db=@($delb|Where-Object{ $c=Ctr $_.bounds; $c[1] -ge $minY -and $c[1] -le $maxY })[0]
  if(-not $db){ $db=$delb[0] }
  $bc=Ctr $db.bounds
  Write-Output "TAP_CONFIRM=$($bc[0]),$($bc[1])"
  TapXY $bc[0] $bc[1] 2400
}
Start-Sleep -Seconds 2
$a=Cap "D3_${Target}_04_after"
$gone=@($a.SelectNodes("//node")|Where-Object{$_.text -eq $Target}).Count -eq 0
Write-Output "TARGET_GONE=$gone"
Write-Output "ROWS_AFTER: $(RowList $a)"
