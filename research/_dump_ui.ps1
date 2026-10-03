# dump_ui.ps1 — safe wrapper for UI dump
$ErrorActionPreference = 'SilentlyContinue'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$serial = 'emulator-5554'

$arg1 = $args[0]
$xmlFile = 'C:\Temp\dump_ui.xml'

if ($arg1 -eq 'dump') {
    if (Test-Path $xmlFile) { Remove-Item $xmlFile -Force }
    & $adb -s $serial shell rm -f /sdcard/dump_ui.xml 2>&1 | Out-Null
    & $adb -s $serial shell uiautomator dump /sdcard/dump_ui.xml 2>&1 | Out-Null
    & $adb -s $serial pull /sdcard/dump_ui.xml $xmlFile 2>&1 | Out-Null
    if (-not (Test-Path $xmlFile)) { Write-Output "pull failed"; exit 1 }
    Write-Output "saved $xmlFile"
    exit 0
}

if ($arg1 -eq 'texts') {
    [xml]$xml = Get-Content $xmlFile
    $xml.SelectNodes('//node[@text!=""]') | ForEach-Object {
        Write-Output ($_.text + '@' + $_.bounds)
    }
    Write-Output '--- content-desc ---'
    $xml.SelectNodes('//node[@content-desc!=""]') | ForEach-Object {
        Write-Output ($_.GetAttribute('content-desc') + '@' + $_.bounds)
    }
    exit 0
}

if ($arg1 -eq 'tap') {
    $target = $args[1]
    [xml]$xml = Get-Content $xmlFile
    $hit = $null
    foreach ($n in $xml.SelectNodes('//node')) {
        if ($n.text -eq $target) { $hit = $n; break }
    }
    if (-not $hit) { Write-Output "NOT FOUND (text=$target)"; exit 2 }
    Write-Output ("found {0} @ {1}" -f $target, $hit.bounds)
    if ($hit.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { Write-Output 'BAD BOUNDS'; exit 3 }
    $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    & $adb -s $serial shell input tap $cx $cy 2>&1 | Out-Null
    Write-Output ("tapped {0},{1}" -f $cx, $cy)
    exit 0
}

if ($arg1 -eq 'tap_bounds') {
    $bx = [int]$args[1]
    $by = [int]$args[2]
    & $adb -s $serial shell input tap $bx $by 2>&1 | Out-Null
    Write-Output ("tapped {0},{1}" -f $bx, $by)
    exit 0
}

if ($arg1 -eq 'shot') {
    $name = $args[1]
    $dir = 'C:\sensorsdata\main\program\opencode-android-shell\research\v8_verify\err_review'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $target = Join-Path $dir "$name.png"
    & $adb -s $serial shell screencap -p /sdcard/$name.png 2>&1 | Out-Null
    & $adb -s $serial pull /sdcard/$name.png $target 2>&1 | Out-Null
    if (Test-Path $target) {
        Write-Output ("saved {0}" -f $target)
    } else {
        Write-Output ("FAILED to save to {0}" -f $target)
    }
    exit 0
}

if ($arg1 -eq 'key') {
    $code = $args[1]
    & $adb -s $serial shell input keyevent $code 2>&1 | Out-Null
    Write-Output ("keyevent {0}" -f $code)
    exit 0
}

if ($arg1 -eq 'type') {
    $text = $args[1]
    & $adb -s $serial shell input text ($text -replace ' ', '%s') 2>&1 | Out-Null
    Write-Output ("typed: {0}" -f $text)
    exit 0
}

Write-Output 'usage: dump | texts | tap <text> | tap_bounds <x> <y> | shot <name> | key <code> | type <text>'
exit 1