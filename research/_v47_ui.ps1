param([string]$Serial='emulator-5554')
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$tmp=Join-Path $env:TEMP 'v47ui.xml'
if (Test-Path $tmp) { Remove-Item $tmp -Force }
& $adb -s $Serial shell uiautomator dump /sdcard/v47ui.xml *>$null
& $adb -s $Serial pull /sdcard/v47ui.xml $tmp *>$null
$xml=[xml](Get-Content $tmp -Raw)
foreach ($n in $xml.SelectNodes('//node[@text!="" or @content-desc!=""]')) {
  $t=$n.text; if (-not $t) { $t='' }
  $d=$n.GetAttribute('content-desc')
  if ($t -eq '' -and $d -eq '') { continue }
  $sc=$n.GetAttribute('scrollable')
  "$($n.bounds) t=[$t] d=[$d] scr=$sc"
}
