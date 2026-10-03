$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$S="emulator-5562"
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98"

function Dump([string]$n){
  foreach($t in 1..5){
    & $adb -s $S shell uiautomator dump --compressed /sdcard/u2.xml | Out-Null
    & $adb -s $S pull /sdcard/u2.xml "$d\$n.xml" 2>$null | Out-Null
    if(Test-Path "$d\$n.xml"){ & $adb -s $S shell rm /sdcard/u2.xml 2>$null | Out-Null; return $true }
    Start-Sleep -Milliseconds 800
  }
  return $false
}
function Shot([string]$n){
  & $adb -s $S shell screencap -p /sdcard/u2p.png | Out-Null
  & $adb -s $S pull /sdcard/u2p.png "$d\$n.png" | Out-Null
  & $adb -s $S shell rm /sdcard/u2p.png 2>$null | Out-Null
}
function Find([string]$n,[string]$pat){
  $f="$d\$n.xml"
  if(-not (Test-Path $f)){ return "NOTFOUND-DUMP" }
  $x=Get-Content $f -Raw
  foreach($m in [regex]::Matches($x,'<node\b[^>]*>')){
    $s=$m.Value; $t=$null
    foreach($a in @('text','content-desc')){
      $g=[regex]::Match($s,"$a=`"([^`"]*)`"")
      if($g.Success -and $g.Groups[1].Value.Trim()){ $t=$g.Groups[1].Value; break }
      $g2=[regex]::Match($s,"$a='([^']*)'")
      if($g2.Success -and $g2.Groups[1].Value.Trim()){ $t=$g2.Groups[1].Value; break }
    }
    if($null -eq $t){ continue }
    if($t -notmatch $pat){ continue }
    $b=[regex]::Match($s,'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if(-not $b.Success){ continue }
    "{0}|[{1},{2}][{3},{4}]|c={5},{6}" -f $t,$b.Groups[1].Value,$b.Groups[2].Value,$b.Groups[3].Value,$b.Groups[4].Value,[int](([int]$b.Groups[1].Value+[int]$b.Groups[3].Value)/2),[int](([int]$b.Groups[2].Value+[int]$b.Groups[4].Value)/2)
  }
}
function Probe([string]$tag){
  $r = & $adb -s $S shell dumpsys input_method 2>$null | Select-String "mInputShown"
  "[$tag] $r"
}
function TapAt([string]$n,[string]$pat,[string]$tag){
  $h = Find $n $pat | Select-Object -First 1
  if($h -match 'c=(\d+),(\d+)'){
    & $adb -s $S shell input tap $Matches[1] $Matches[2]
    "$tag -> tapped $h"
    return $true
  }
  "$tag -> NOT FOUND ($pat)"
  return $false
}
