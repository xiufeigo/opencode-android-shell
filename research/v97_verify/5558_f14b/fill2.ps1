param([int]$X, [int]$Y, [string]$Text, [int]$Clear=0, [string]$Tag="f", [int]$MaxTry=3)
$ErrorActionPreference='Continue'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5558_f14b"
$global:GX=$null
function Dmp($t){
  & $adb -s emulator-5558 shell uiautomator dump /sdcard/w.xml 2>&1 | Out-Null
  & $adb -s emulator-5558 pull /sdcard/w.xml "$dir\$t.xml" 2>&1 | Out-Null
  $global:GX = [xml](Get-Content "$dir\$t.xml" -Raw -Encoding UTF8)
}
$got=$false
for($try=1;$try -le $MaxTry -and -not $got;$try++){
  Dmp "${Tag}_pre$try"
  # recompute target bounds fresh each try
  $tb=$null
  foreach($n in $global:GX.SelectNodes('//node')){
    if($n.class -eq 'android.widget.EditText' -and $n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
      $bx=[int]$Matches[1];$by=[int]$Matches[2];$ex=[int]$Matches[3];$ey=[int]$Matches[4]
      if($X -ge $bx -and $X -le $ex -and $Y -ge $by -and $Y -le $ey){ $tb=$n; break }
    }
  }
  if(-not $tb){ Write-Output "try$try NO-EDITTEXT-AT($X,$Y)"; Start-Sleep -Milliseconds 800; continue }
  $mk=if($tb.password -eq 'true'){'MASKED'}else{'plain'}
  & $adb -s emulator-5558 shell input tap $X $Y
  Start-Sleep -Milliseconds 1300
  Dmp "${Tag}_tap$try"
  $f=@(); foreach($n in $global:GX.SelectNodes('//node')){ if($n.focused -eq 'true'){ $f+=$n } }
  if($f.Count -eq 0){ Write-Output "try$try NO-FOCUS (target $mk $($tb.bounds))"; Start-Sleep -Milliseconds 900; continue }
  Write-Output ("try{0} FOCUS-OK target={1} {2} focused={3}" -f $try,$mk,$tb.bounds,($f[0].bounds))
  $got=$true
}
if(-not $got){ Write-Output "TAG=$Tag FAILED-TO-FOCUS after $MaxTry tries"; exit 1 }
if($Clear -gt 0){
  & $adb -s emulator-5558 shell input keyevent 123
  Start-Sleep -Milliseconds 400
  for($i=0;$i -lt $Clear;$i++){ & $adb -s emulator-5558 shell input keyevent 67 }
  Start-Sleep -Milliseconds 800
}
$i=0
while($i -lt $Text.Length){
  $len=[Math]::Min(8, $Text.Length-$i)
  $seg=$Text.Substring($i,$len)
  & $adb -s emulator-5558 shell input text ($seg -replace ' ','%s')
  $i+=$len
  if($i -lt $Text.Length){ Start-Sleep -Milliseconds 1500 }
}
Start-Sleep -Milliseconds 1600
Dmp "${Tag}_done"
foreach($n in $global:GX.SelectNodes('//node')){
  if($n.class -eq 'android.widget.EditText'){
    $mk=if($n.password -eq 'true'){'MASKED'}else{'plain'}
    Write-Output ("  FINAL {0} {1} '{2}'" -f $n.bounds,$mk,$n.text)
  }
}
& $adb -s emulator-5558 shell input keyevent 61
