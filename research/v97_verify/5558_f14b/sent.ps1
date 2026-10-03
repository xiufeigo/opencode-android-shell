param([string]$Tag, [int]$X, [int]$Y, [string]$Sentinel)
$ErrorActionPreference='Continue'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5558_f14b"
$global:GX=$null
function Dmp($t){
  & $adb -s emulator-5558 shell uiautomator dump /sdcard/w.xml 2>&1 | Out-Null
  & $adb -s emulator-5558 pull /sdcard/w.xml "$dir\$t.xml" 2>&1 | Out-Null
  $global:GX = [xml](Get-Content "$dir\$t.xml" -Raw -Encoding UTF8)
}
Dmp "${Tag}_a"
$tb=$null
foreach($n in $global:GX.SelectNodes('//node')){
  if($n.bounds -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]'){
    $bx=[int]$Matches[1];$by=[int]$Matches[2];$ex=[int]$Matches[3];$ey=[int]$Matches[4]
    if($X -ge $bx -and $X -le $ex -and $Y -ge $by -and $Y -le $ey){ $tb=$n; break }
  }
}
if(-not $tb){ Write-Output "TAG=$Tag NO-NODE-AT($X,$Y)"; exit 1 }
Write-Output ("TAG={0} tap({1},{2}) class={3} bounds={4}" -f $Tag,$X,$Y,$tb.class,$tb.bounds)
& $adb -s emulator-5558 shell input tap $X $Y
Start-Sleep -Milliseconds 1200
Dmp "${Tag}_b"
$f=@()
foreach($n in $global:GX.SelectNodes('//node')){ if($n.focused -eq 'true'){ $f+=$n } }
if($f.Count -eq 0){ Write-Output "TAG=$Tag NO-FOCUS" }
foreach($n in $f){ Write-Output ("  FOCUS class={0} bounds={1} text='{2}'" -f $n.class,$n.bounds,$n.text) }
& $adb -s emulator-5558 shell input text $Sentinel
Start-Sleep -Milliseconds 1600
Dmp "${Tag}_c"
$ok=$false
foreach($n in $global:GX.SelectNodes('//node')){
  if($n.class -eq 'android.widget.EditText'){
    $mk=if($n.password -eq 'true'){'MASKED'}else{'plain'}
    $v="$($n.text)"
    $hit=if($v -like "*$Sentinel*"){' <<SENTINEL-OK>>'}else{''}
    Write-Output ("  READ {0} {1} text='{2}'{3}" -f $n.bounds,$mk,$v,$hit)
    if($v -like "*$Sentinel*"){$ok=$true}
  }
}
if(-not $ok){ Write-Output "TAG=$Tag SENTINEL-NOT-LANDED" }
