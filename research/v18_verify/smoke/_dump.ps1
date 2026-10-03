# v18 smoke helper: dump UI, show focused/edittext nodes
param([string]$Serial = 'emulator-5558', [string]$Mode = 'all')
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$tmp = Join-Path $env:TEMP 'v18dump.xml'
if (Test-Path $tmp) { Remove-Item $tmp -Force }
& $adb -s $Serial shell rm -f /sdcard/v18dump.xml 2>&1 | Out-Null
& $adb -s $Serial shell uiautomator dump /sdcard/v18dump.xml 2>&1 | Out-Null
& $adb -s $Serial pull /sdcard/v18dump.xml $tmp 2>&1 | Out-Null
if (-not (Test-Path $tmp)) { Write-Output 'DUMP FAILED'; exit 1 }
[xml]$x = Get-Content $tmp -Raw
if ($Mode -eq 'focus' -or $Mode -eq 'all') {
    foreach ($n in $x.SelectNodes('//node')) {
        if ($n.GetAttribute('focused') -eq 'true') {
            Write-Output ("FOCUSED cls={0} text=[{1}] bounds={2}" -f $n.class, $n.text, $n.bounds)
        }
    }
}
if ($Mode -eq 'edit' -or $Mode -eq 'all') {
    foreach ($n in $x.SelectNodes('//node')) {
        if ($n.class -match 'EditText|TextField|TextInput') {
            Write-Output ("EDIT cls={0} text=[{1}] hint=[{2}] focus={3} bounds={4}" -f $n.class, $n.text, $n.GetAttribute('hint'), $n.GetAttribute('focused'), $n.bounds)
        }
    }
}
if ($Mode -eq 'texts' -or $Mode -eq 'all') {
    ($x.SelectNodes('//node[@text!=""]') | ForEach-Object { "$($_.text)@$($_.bounds)" }) -join ' | '
    Write-Output '--- desc ---'
    ($x.SelectNodes('//node[@content-desc!=""]') | ForEach-Object { "$($_.GetAttribute('content-desc'))@$($_.bounds)" }) -join ' | '
}
