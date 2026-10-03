param([string]$Serial='emulator-5560',[string]$Filter='')
$adb='C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s $Serial shell uiautomator dump /sdcard/uix.xml | Out-Null
& $adb -s $Serial pull /sdcard/uix.xml $env:TEMP\uix.xml | Out-Null
[xml]$x = Get-Content $env:TEMP\uix.xml -Raw
$x.SelectNodes('//node') | Where-Object { $_.text -ne '' -or $_.'content-desc' -ne '' } | ForEach-Object {
  $t = if ($_.text) { $_.text } else { $_.'content-desc' }
  if ($Filter -eq '' -or $t -like "*$Filter*") { "{0,-40} {1,-30} {2}" -f $t, $_.class, $_.bounds }
}
