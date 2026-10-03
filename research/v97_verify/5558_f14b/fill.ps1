param([int]$X, [int]$Y, [string]$Text, [int]$Clear=0, [string]$Tag="f")
$ErrorActionPreference='Continue'
$adb="C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5558_f14b"
$global:GX=$null
function Dmp($t){
  & $adb -s emulator-5558 shell uiautomator dump /sdcard/w.xml 2>&1 | Out-Null
  & $adb -s emulator-5558 pull /sdcard/w.xml "$dir\$t.xml" 2>&1 | Out-Null
  $global:GX = [xml](Get-Content "$dir\$t.xml" -Raw -Encoding UTF8)
}
& $adb -s emulator-5558 shell input tap $X $Y
Start-Sleep -Milliseconds 1100
Dmp "${Tag}_tap"
$f=@(); foreach($n in $global:GX.SelectNodes('//node')){ if($n.focused -eq 'true'){ $f+=$n } }
if($f.Count -eq 0){ Write-Output "NO-FOCUS at ($X,$Y)"; exit 1 }
Write-Output ("FOCUS ok: " + ($f | ForEach-Object { $_.class + " " + $_.bounds }) -join ' ')
if($Clear -gt 0){
  & $adb -s emulator-5558 shell input keyevent 123
  Start-Sleep -Milliseconds 400
  for($i=0;$i -lt $Clear;$i++){ & $adb -s emulator-5558 shell input keyevent 67 }
  Start-Sleep -Milliseconds 700
  Dmp "${Tag}_clr"
  foreach($n in $global:GX.SelectNodes('//node')){ if($n.class -eq 'android.widget.EditText'){ $mk=if($n.password -eq 'true'){'MASKED'}else{'plain'}; Write-Output ("  AFTER-CLEAR {0} {1} '{2}'" -f $n.bounds,$mk,$n.text) } }
}
# type in <=8 char segments, 1.5s between
$i=0
while($i -lt $Text.Length){
  $len=[Math]::Min(8, $Text.Length-$i)
  $seg=$Text.Substring($i,$len)
  $enc=$seg -replace ' ','%s'
  & $adb -s emulator-5558 shell input text $enc
  $i+=$len
  if($i -lt $Text.Length){ Start-Sleep -Milliseconds 1500 }
}
Start-Sleep -Milliseconds 1500
Dmp "${Tag}_done"
foreach($n in $global:GX.SelectNodes('//node')){
  if($n.class -eq 'android.widget.EditText'){
    $mk=if($n.password -eq 'true'){'MASKED'}else{'plain'}
    Write-Output ("  FINAL {0} {1} '{2}'" -f $n.bounds,$mk,$n.text)
  }
}
& $adb -s emulator-5558 shell input keyevent 61
