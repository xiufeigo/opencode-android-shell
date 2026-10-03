$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5556"
$outDir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"
$pre = "fs"   # final sweep prefix

function Dump([string]$tag) {
    $t = "$pre`_$tag"
    & $adb -s $dev shell uiautomator dump --compressed "/sdcard/$t.xml" | Out-Null
    & $adb -s $dev pull "/sdcard/$t.xml" "$outDir\$t.xml" 2>&1 | Out-Null
    $p = "$outDir\$t.xml"
    if (Test-Path $p) { return (Get-Content $p -Raw) } else { return "" }
}

function Shot([string]$tag) {
    $t = "$pre`_$tag"
    & $adb -s $dev shell screencap -p "/sdcard/$t.png" | Out-Null
    & $adb -s $dev pull "/sdcard/$t.png" "$outDir\$t.png" 2>&1 | Out-Null
}

function Texts([string]$xml) {
    $m = [regex]::Matches($xml, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value }
    $m += [regex]::Matches($xml, "text='([^']*)'") | ForEach-Object { $_.Groups[1].Value }
    return ($m | Where-Object { $_ -ne "" } | Select-Object -Unique)
}

function Txt([string]$tag) { Write-Output ((Texts (Dump $tag)) -join " | ") }

function Bnd([string]$xml, [string]$label) {
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

function DescBnd([string]$xml, [string]$label) {
    foreach ($q in @("content-desc=`"$label`"", "content-desc='$label'")) {
        $m = [regex]::Match($xml, [regex]::Escape($q) + '[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($m.Success) {
            $x1=[int]$m.Groups[1].Value; $y1=[int]$m.Groups[2].Value; $x2=[int]$m.Groups[3].Value; $y2=[int]$m.Groups[4].Value
            return @([int](($x1+$x2)/2), [int](($y1+$y2)/2))
        }
    }
    return $null
}

# Click by visible text: dump, parse bounds, tap
function TapTxt([string]$tag, [string]$label) {
    $xml = Dump $tag
    $c = Bnd $xml $label
    if ($null -eq $c) { Write-Output "TAPFAIL: '$label' not found in $tag"; return $false }
    & $adb -s $dev shell input tap $c[0] $c[1]
    Write-Output "TAP: '$label' @ $($c[0]),$($c[1])"
    return $true
}

function TapDesc([string]$tag, [string]$label) {
    $xml = Dump $tag
    $c = DescBnd $xml $label
    if ($null -eq $c) { Write-Output "TAPFAIL desc: '$label' not found in $tag"; return $false }
    & $adb -s $dev shell input tap $c[0] $c[1]
    Write-Output "TAPDESC: '$label' @ $($c[0]),$($c[1])"
    return $true
}

function Focus() {
    $f = & $adb -s $dev shell dumpsys window 2>$null | Select-String -Pattern 'mCurrentFocus|mFocusedApp'
    return ($f -join " ;; ")
}

# type text in <=8 char chunks, spaces via keyevent 62
function TypeText([string]$s) {
    $parts = $s -split '(\s)'
    foreach ($p in $parts) {
        if ($p -eq "") { continue }
        if ($p -eq " ") { & $adb -s $dev shell input keyevent 62; continue }
        if ($p.Length -gt 8) {
            for ($i=0; $i -lt $p.Length; $i+=8) {
                $len = [Math]::Min(8, $p.Length - $i)
                & $adb -s $dev shell input text $p.Substring($i,$len)
            }
        } else {
            & $adb -s $dev shell input text $p
        }
    }
}
