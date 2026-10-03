param([string]$Serial = 'emulator-5556', [int]$Seconds = 90, [int]$Interval = 2)
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$end = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $end) {
    & $adb -s $Serial shell rm -f /sdcard/p.xml 2>&1 | Out-Null
    $d = (& $adb -s $Serial shell uiautomator dump /sdcard/p.xml 2>&1) -join ' '
    if ($d -match 'dumped to') {
        & $adb -s $Serial pull /sdcard/p.xml (Join-Path $env:TEMP 'p.xml') 2>&1 | Out-Null
        $x = Get-Content (Join-Path $env:TEMP 'p.xml') -Raw
        $descs = [regex]::Matches($x, 'content-desc="([^"]*)"\s+bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        $btn = 'none'
        foreach ($m in $descs) {
            $x1 = [int]$m.Groups[2].Value
            if ($x1 -gt 1290 -and [int]$m.Groups[3].Value -gt 600) { $btn = $m.Groups[1].Value }
        }
        Write-Output ("{0:HH:mm:ss} composer_btn='{1}'" -f (Get-Date), $btn)
    } else { Write-Output 'dumpfail' }
    Start-Sleep -Seconds $Interval
}
