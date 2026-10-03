$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5562"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98"

function Dump([string]$n){
  foreach($t in 1..5){
    & $adb -s $S shell uiautomator dump --compressed /sdcard/d3.xml | Out-Null
    if(Test-Path "$d\$n.xml"){ Remove-Item "$d\$n.xml" -Force }
    & $adb -s $S pull /sdcard/d3.xml "$d\$n.xml" 2>$null | Out-Null
    & $adb -s $S shell rm -f /sdcard/d3.xml 2>$null | Out-Null
    if(Test-Path "$d\$n.xml"){ return $true }
    Start-Sleep -Milliseconds 800
  }
  return $false
}
function Shot([string]$n){
  & $adb -s $S shell screencap -p /sdcard/d3p.png | Out-Null
  if(Test-Path "$d\$n.png"){ Remove-Item "$d\$n.png" -Force }
  & $adb -s $S pull /sdcard/d3p.png "$d\$n.png" 2>$null | Out-Null
  & $adb -s $S shell rm -f /sdcard/d3p.png 2>$null | Out-Null
}
# matches BOTH text="..." and text='...'
function Find([string]$n,[string]$pat){
  $f="$d\$n.xml"
  if(-not (Test-Path $f)){ return "NOTFOUND-DUMP" }
  $x=Get-Content $f -Raw
  foreach($m in [regex]::Matches($x,'<node\b[^>]*>')){
    $ns=$m.Value; $t=$null
    foreach($a in @('text','content-desc')){
      $g=[regex]::Match($ns,"$a=`"([^`"]*)`"")
      if($g.Success -and $g.Groups[1].Value.Trim()){ $t=$g.Groups[1].Value; break }
      $g2=[regex]::Match($ns,"$a='([^']*)'")
      if($g2.Success -and $g2.Groups[1].Value.Trim()){ $t=$g2.Groups[1].Value; break }
    }
    if($null -eq $t){ continue }
    if($t -notmatch $pat){ continue }
    $b=[regex]::Match($ns,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if(-not $b.Success){ continue }
    "{0}|[{1},{2}][{3},{4}]|c={5},{6}" -f $t,$b.Groups[1].Value,$b.Groups[2].Value,$b.Groups[3].Value,$b.Groups[4].Value,[int](([int]$b.Groups[1].Value+[int]$b.Groups[3].Value)/2),[int](([int]$b.Groups[2].Value+[int]$b.Groups[4].Value)/2)
  }
}
# ALL nodes with bounds, class, text, cd, scrollable, clickable, focusable
function AllNodes([string]$n){
  $f="$d\$n.xml"
  if(-not (Test-Path $f)){ return @() }
  $x=Get-Content $f -Raw
  $out=@()
  foreach($m in [regex]::Matches($x,'<node\b[^>]*>')){
    $ns=$m.Value
    $b=[regex]::Match($ns,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if(-not $b.Success){ continue }
    $x1=[int]$b.Groups[1].Value; $y1=[int]$b.Groups[2].Value
    $x2=[int]$b.Groups[3].Value; $y2=[int]$b.Groups[4].Value
    $cl=[regex]::Match($ns,'class="([^"]*)"').Groups[1].Value
    $tx=$null; $cd=$null
    $g=[regex]::Match($ns,'text="([^"]*)"'); if($g.Success -and $g.Groups[1].Value.Trim()){$tx=$g.Groups[1].Value}
    $g2=[regex]::Match($ns,"text='([^']*)'"); if($g2.Success -and $g2.Groups[1].Value.Trim()){$tx=$g2.Groups[1].Value}
    $g3=[regex]::Match($ns,'content-desc="([^"]*)"'); if($g3.Success -and $g3.Groups[3].Value){$cd=$g3.Groups[1].Value}
    $g4=[regex]::Match($ns,"content-desc='([^']*)'"); if($g4.Success -and $g4.Groups[1].Value.Trim()){$cd=$g4.Groups[1].Value}
    $sc=[regex]::Match($ns,'scrollable="true"').Success
    $ck=[regex]::Match($ns,'clickable="true"').Success
    $out += [pscustomobject]@{ t=$tx; cd=$cd; cls=$cl; x1=$x1;y1=$y1;x2=$x2;y2=$y2; w=($x2-$x1); h=($y2-$y1); scroll=$sc; click=$ck }
  }
  $out
}
function ImeShown(){
  $r = & $adb -s $S shell dumpsys input_method 2>$null | Select-String "mInputShown"
  if($r -match 'mInputShown=(true|false)'){ return $Matches[1] }
  return "unknown"
}
function TapC([string]$n,[string]$pat){
  $h = Find $n $pat | Select-Object -First 1
  if($h -match 'c=(\d+),(\d+)'){
    & $adb -s $S shell input tap $Matches[1] $Matches[2]
    return "tapped $h"
  }
  return "NOTFOUND ($pat)"
}
# compliant text input: <=8 char segments, space = keyevent 62
function TypeSeg([string]$s){
  & $adb -s $S shell input text $s
}
function SendKey([string]$k){ & $adb -s $S shell input keyevent $k }
