$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5556"
$outDir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"

function Dump([string]$tag) {
    & $adb -s $dev shell uiautomator dump "/sdcard/$tag.xml" | Out-Null
    & $adb -s $dev pull "/sdcard/$tag.xml" "$outDir\$tag.xml" 2>&1 | Out-Null
    $p = "$outDir\$tag.xml"
    if (Test-Path $p) { return (Get-Content $p -Raw) } else { return "" }
}

function Shot([string]$tag) {
    & $adb -s $dev shell screencap -p "/sdcard/$tag.png" | Out-Null
    & $adb -s $dev pull "/sdcard/$tag.png" "$outDir\$tag.png" 2>&1 | Out-Null
}

# Match BOTH text="..." and text='...'  (D9 discipline)
function Texts([string]$xml) {
    $m = [regex]::Matches($xml, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value }
    $m += [regex]::Matches($xml, "text='([^']*)'") | ForEach-Object { $_.Groups[1].Value }
    return ($m | Where-Object { $_ -ne "" } | Select-Object -Unique)
}

function Center([string]$xml, [string]$label) {
    foreach ($q in @("text=`"$label`"", "text='$label'")) {
        $m = [regex]::Match($xml, [regex]::Escape($q) + '[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $m.Success) { $m = [regex]::Match($xml, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*' + [regex]::Escape($q)) }
        if ($m.Success) {
            $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
            return @([int](($x1+$x2)/2), [int](($y1+$y2)/2))
        }
    }
    return $null
}

function ContentDesc([string]$xml, [string]$label) {
    foreach ($q in @("content-desc=`"$label`"", "content-desc='$label'")) {
        $m = [regex]::Match($xml, [regex]::Escape($q) + '[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($m.Success) {
            $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
            return @([int](($x1+$x2)/2), [int](($y1+$y2)/2))
        }
    }
    return $null
}

# Classify the Changes body from full text set
function Classify([string]$xml) {
    $t = (Texts $xml) -join " | "
    if ($t -match "No uncommitted changes yet") { return @("FALSE_EMPTY", $t) }
    if ($t -match "timed out or failed")             { return @("FAILED", $t) }
    if ($t -match "Loading")                          { return @("LOADING", $t) }
    return @("OTHER", $t)
}
