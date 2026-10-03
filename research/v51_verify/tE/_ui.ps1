param([string]$Serial='emulator-5554', [switch]$Clickable, [string]$Filter='')
$ErrorActionPreference='Continue'
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$tmp=Join-Path $env:TEMP "tui_$Serial.xml"
& $adb -s $Serial shell uiautomator dump /sdcard/tui.xml | Out-Null
& $adb -s $Serial pull /sdcard/tui.xml $tmp 2>&1 | Out-Null
$x=[xml](Get-Content $tmp -Raw)
foreach($n in $x.SelectNodes('//node')){
  $t=$n.GetAttribute('text'); $d=$n.GetAttribute('content-desc'); $cd=$n.GetAttribute('clickable')
  if($Clickable){ if($cd -ne 'true'){continue} } else { if($cd -eq 'true'){continue} }
  if($Filter -ne '' -and "$t $d" -notlike "*$Filter*"){continue}
  "$cd | [$t] | <$d> | $($n.bounds)"
}
