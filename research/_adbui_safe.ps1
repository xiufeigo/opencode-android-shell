# adbui_safe.ps1 — variant that suppresses the noisy "1 file pulled" stderr
$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$serial = 'emulator-5554'

function Get-UiSafe {
    $tmp = Join-Path $env:TEMP 'adbui.xml'
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    & $adb -s $serial shell rm -f /sdcard/adbui.xml 2>$null | Out-Null
    & $adb -s $serial shell uiautomator dump /sdcard/adbui.xml 2>$null | Out-Null
    & $adb -s $serial pull /sdcard/adbui.xml $tmp 2>$null | Out-Null
    if (-not (Test-Path $tmp)) { throw 'pull failed' }
    [xml](Get-Content $tmp -Raw)
}

if ($args.Count -eq 0) {
    Write-Output "usage: texts | tap <text> | shot <name>"
    exit 1
}

switch ($args[0]) {
    'texts' {
        $xml = Get-UiSafe
        $xml.SelectNodes('//node[@text!=""]') | ForEach-Object {
            $b = $_.bounds
            Write-Output ("{0}@{1}" -f $_.text, $b)
        }
        Write-Output '--- content-desc ---'
        $xml.SelectNodes('//node[@content-desc!=""]') | ForEach-Object {
            $b = $_.bounds
            Write-Output ("{0}@{1}" -f $_.GetAttribute('content-desc'), $b)
        }
    }
    'shot' {
        $name = $args[1]
        $dir = 'C:\sensorsdata\main\program\opencode-android-shell\research\v8_verify\err_review'
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        & $adb -s $serial shell screencap -p /sdcard/$name.png 2>$null | Out-Null
        & $adb -s $serial pull /sdcard/$name.png (Join-Path $dir "$name.png") 2>$null | Out-Null
        Write-Output "saved $dir/$name.png"
    }
    'tap' {
        $target = $args[1]
        $xml = Get-UiSafe
        $hit = $null
        foreach ($n in $xml.SelectNodes('//node')) {
            if ($n.text -eq $target) { $hit = $n; break }
        }
        if (-not $hit) { Write-Output "NOT FOUND (text=$target)"; exit 2 }
        Write-Output "found $target @ $($hit.bounds)"
        if ($hit.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { Write-Output 'BAD BOUNDS'; exit 3 }
        $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        & $adb -s $serial shell input tap $cx $cy 2>$null
        Write-Output "tapped $cx,$cy"
    }
    'tap_bounds' {
        $bx = [int]$args[1]; $by = [int]$args[2]
        & $adb -s $serial shell input tap $bx $by 2>$null
        Write-Output "tapped $bx,$by"
    }
    'key' {
        $code = $args[1]
        & $adb -s $serial shell input keyevent $code 2>$null
        Write-Output "keyevent $code"
    }
    'type' {
        $text = $args[1]
        & $adb -s $serial shell input text ($text -replace ' ', '%s') 2>$null
        Write-Output "typed: $text"
    }
}