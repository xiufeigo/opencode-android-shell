$ErrorActionPreference='Stop'
. "$PSScriptRoot\c2lib.ps1"

$global:NAVED=$false
function Nav(){
  if(-not $global:NAVED){
    & $global:ADB -s $global:DEV shell am force-stop com.xiufeigo.opencodeshell
    Start-Sleep -Seconds 1
    & $global:ADB -s $global:DEV shell monkey -p com.xiufeigo.opencodeshell -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 5
    $global:NAVED=$true
  }
  $x=Dump "Q00"
  if(InServers $x){ return $x }
  if((Find $x "Settings").Count -gt 0){ TapTxt $x "Settings" 0 2500 | Out-Null; $x=Dump "Q01" }
  for($i=0;$i -lt 8;$i++){
    if(InServers $x){ return $x }
    $ts=@($x.SelectNodes("//node")|Where-Object{$_.scrollable -eq "true" -and ((BNum $_.bounds)[3]-(BNum $_.bounds)[1]) -lt 200})|Select-Object -First 1
    if($ts){ $bn=BNum $ts.bounds; $ty=[int](($bn[1]+$bn[3])/2)
      for($k=0;$k -lt 5;$k++){ & $global:ADB -s $global:DEV shell input swipe 950 $ty 150 $ty 200; Start-Sleep -Milliseconds 320 } }
    $x=Dump "Q1$i"
    if((Find $x "Servers").Count -gt 0){ TapTxt $x "Servers" 0 1800 | Out-Null; $x=Dump "Q2$i" }
    if(InServers $x){ return $x }
  }
  return $x
}
# ordinal (1-based) of a row among ALL ssh rows, sorted by y
function Ordinal($x,$name){
  $rows=@(SshRows $x "E2ECHK5554d3c")
  for($i=0;$i -lt $rows.Count;$i++){ if((TxtOf $rows[$i]) -eq $name){ return $i+1 } }
  return -1
}
# The per-row 3-dot button: clickable Button whose y sits within the row's band
function RowDot($x,$rowNode){
  $rn=BNum $rowNode.bounds
  $best=$null
  foreach($b in @($x.SelectNodes("//node")|Where-Object{$_.class -eq "android.widget.Button" -and $_.clickable -eq "true"})){
    $bn=BNum $b.bounds
    if($bn[1] -ge ($rn[1]-40) -and $bn[3] -le ($rn[3]+220)){ if(-not $best -or $bn[1] -lt (BNum $best.bounds)[1]){ $best=$b } }
  }
  return $best
}

$target=$args[0]
$x=Nav
Cap "D00_before"
$before=@(RowNames $x)
Write-Output "BEFORE ($($before.Count)): $($before -join ' ; ')"
$vp=PanelViewport $x
Write-Output "VIEWPORT=$vp  VP_Y=[$((BNum $vp)[1]),$((BNum $vp)[3])]"
$ord=Ordinal $x $target
Write-Output "TARGET=$target ORDINAL=$ord"

# scroll the panel body until the target row is present and fully inside the viewport
$vn=BNum $vp
for($a=0;$a -lt 6;$a++){
  $x=Nav
  $hit=@(SshRows $x "E2ECHK5554d3c" | Where-Object { (TxtOf $_) -eq $target })[0]
  if($hit){ $hn=BNum $hit.bounds
    if($hn[1] -ge $vn[1] -and $hn[1] -lt $vn[3]){ Write-Output "  target in view at y=$($hn[1]) after $a scroll(s)"; break }
    Write-Output "  target y=$($hn[1]) not in [$($vn[1]),$($vn[3])], scrolling"
  } else { Write-Output "  target not in tree, scrolling ($a)" }
  # fast flick only (~100px/80ms)
  Flick $x ([int]($vn[3]-150)) ([int]($vn[1]+150))
}
$x=Nav
$hit=@(SshRows $x "E2ECHK5554d3c" | Where-Object { (TxtOf $_) -eq $target })[0]
if(-not $hit){ Write-Output "TARGET_NOT_FOUND"; exit 1 }
$hn=BNum $hit.bounds
Write-Output "TARGET_ROW bounds=$($hit.bounds) y=$($hn[1])"
Cap "D01_target"
$dot=RowDot $x $hit
if(-not $dot){ Write-Output "NO_DOT_BUTTON for target"; exit 1 }
Write-Output "DOT bounds=$($dot.bounds)"
$dc=Ctr $dot.bounds
TapXY $dc[0] $dc[1] 1800
$m=Dump "D02_menu"; Cap "D02_menu"
$dele=@(Find $m "Delete")[0]
if(-not $dele){ Write-Output "NO_DELETE_IN_MENU"; exit 1 }
$dlb=$dele.bounds
$dc2=Ctr $dlb
Write-Output "MENU_DELETE bounds=$dlb"
TapXY $dc2[0] $dc2[1] 2000
$bar=Dump "D03_bar"; Cap "D03_bar"
$bn=BNum $bar.bounds
$vp2=PanelViewport $bar
$vn2=BNum $vp2
$barTitle=@(Find $bar "Delete SSH server?")[0]
$cancel=@($bar.SelectNodes("//node")|Where-Object{(TxtOf $_) -eq "Cancel"})
$delb=@($bar.SelectNodes("//node")|Where-Object{ (TxtOf $_) -eq "Delete" -and $_.class -eq "android.widget.Button" })
$delb2=@($bar.SelectNodes("//node")|Where-Object{ (TxtOf $_) -eq "Delete" -and (BNum $_.bounds)[1] -gt ($vn2[1]) })
Write-Output "BAR_TITLE=$($barTitle.bounds)"
$minY=99999; $maxY=0
foreach($n in @($barTitle)+$cancel+$delb2){ if($n){ $q=BNum $n.bounds; if($q[1] -lt $minY){$minY=$q[1]}; if($q[3] -gt $maxY){$maxY=$q[3]} } }
Write-Output "CONFIRM_BAR_Y=[$minY, $maxY]"
Write-Output "VIEWPORT=$vp2  VP_Y=[$($vn2[1]), $($vn2[3])]"
$inView = ($minY -ge $vn2[1]) -and ($maxY -le $vn2[3])
Write-Output "GEOMETRY_IN_VIEW=$inView"
$delbtn=@($delb2|Sort-Object{(BNum $_.bounds)[1]})[0]
if(-not $delbtn){ Write-Output "NO_CONFIRM_DELETE_BTN"; exit 1 }
Write-Output "CONFIRM_DELETE bounds=$($delbtn.bounds) clickable=$($delbtn.clickable) enabled=$($delbtn.enabled)"
$cc=Ctr $delbtn.bounds
TapXY $cc[0] $cc[1] 2500
Start-Sleep -Seconds 2
$after=Nav
Cap "D04_after"
$la=@(RowNames $after)
Write-Output "AFTER ($($la.Count)): $($la -join ' ; ')"
$gone = -not (($la | Where-Object { $_ -like "*$target*" }).Count -gt 0)
Write-Output "TARGET_GONE=$gone"
$others=@($la|Where-Object{ $_ -notlike "*$target*" })
Write-Output "OTHER_ROWS_SURVIVED=$($others.Count) :: $($others -join ' ; ')"
