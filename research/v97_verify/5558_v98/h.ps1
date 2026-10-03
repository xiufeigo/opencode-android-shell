# h.ps1 - D3-4 key variant helpers for emulator-5558
# Coordinate resolution ALWAYS from a fresh runtime dump; matches both text=".." and text='..'
$ErrorActionPreference = 'Continue'
$script:ADB  = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$script:DEV  = 'emulator-5558'
$script:PKG  = 'com.xiufeigo.opencodeshell'
$script:E    = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5558_v98\evidence"
New-Item -ItemType Directory -Force -Path $script:E | Out-Null

function SH([string]$c) { (& $script:ADB -s $script:DEV shell $c 2>&1 | Out-String).TrimEnd() }

function AssertFocus {
    $f = SH 'dumpsys window | grep mCurrentFocus'
    # dumpsys prints the fully-qualified activity: com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity
    if ($f -match 'com\.xiufeigo\.opencodeshell/com\.xiufeigo\.opencodeshell\.OcActivity') {
        return $true
    }
    # A Compose DropdownMenu inside the app surfaces as "Pop-Up Window" (no package in the
    # focus line). Accept it ONLY when the pop-up's own owner and the focused app are ours.
    if ($f -match 'Pop-Up Window') {
        $owner = SH "dumpsys window windows | grep -A3 'Pop-Up Window' | grep -m1 'package='"
        $fapp  = SH 'dumpsys window | grep mFocusedApp'
        if ($owner -match 'package=com\.xiufeigo\.opencodeshell' -and $fapp -match 'com\.xiufeigo\.opencodeshell') {
            return $true
        }
    }
    Write-Host "FOCUS-FAIL: $f"
    return $false
}

# Dump UI to a file, return raw xml path
function Dump([string]$tag) {
    $xml = "$script:E\$tag.xml"
    SH 'uiautomator dump --compressed /sdcard/d.xml' | Out-Null
    & $script:ADB -s $script:DEV pull /sdcard/d.xml $xml 2>&1 | Out-Null
    SH 'rm -f /sdcard/d.xml' | Out-Null
    return $xml
}

# Parse every node with any text/content-desc into objects
function Nodes([string]$xmlPath) {
    $raw = Get-Content -Raw $xmlPath
    $out = @()
    # attribute values may be double- or single-quoted
    foreach ($m in [regex]::Matches($raw, '<node\b[^>]*>')) {
        $n = $m.Value
        $get = {
            param($k)
            $mm = [regex]::Match($n, ($k + '="([^"]*)"'))
            if ($mm.Success) { return $mm.Groups[1].Value }
            $mm2 = [regex]::Match($n, ($k + "='([^']*)'"))
            if ($mm2.Success) { return $mm2.Groups[1].Value }
            return ''
        }
        $t  = & $get 'text'
        $cd = & $get 'content-desc'
        $cl = & $get 'class'
        $bd = & $get 'bounds'
        $ck = & $get 'clickable'
        $en = & $get 'enabled'
        if ([string]::IsNullOrWhiteSpace($bd)) { continue }
        $bm = [regex]::Match($bd, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $bm.Success) { continue }
        $x1=[int]$bm.Groups[1].Value; $y1=[int]$bm.Groups[2].Value
        $x2=[int]$bm.Groups[3].Value; $y2=[int]$bm.Groups[4].Value
        $out += [pscustomobject]@{
            Text=$t; Desc=$cd; Class=($cl -replace '.*\.',''); Clickable=$ck; Enabled=$en
            X1=$x1; Y1=$y1; X2=$x2; Y2=$y2
            X=[int](($x1+$x2)/2); Y=[int](($y1+$y2)/2)
            CX=[int](($x1+$x2)/2); CY=[int](($y1+$y2)/2)
            W=($x2-$x1); H=($y2-$y1)
        }
    }
    return $out
}

# Find node whose Text or Desc matches regex; returns first or null
function Find([string]$xmlPath, [string]$pattern) {
    foreach ($n in (Nodes $xmlPath)) {
        if ($n.Text -match $pattern -or $n.Desc -match $pattern) { return $n }
    }
    return $null
}
function FindAll([string]$xmlPath, [string]$pattern) {
    $r = @()
    foreach ($n in (Nodes $xmlPath)) {
        if ($n.Text -match $pattern -or $n.Desc -match $pattern) { $r += $n }
    }
    return $r
}

# Tap by node center; focus-assert first
function TapNode($node, [string]$tag) {
    if (-not (AssertFocus)) { throw "FOCUS LOST before tap $tag" }
    Write-Host "[tap] '$($node.Text)$($node.Desc)' @ $($node.CX),$($node.CY)  [$tag]"
    & $script:ADB -s $script:DEV shell input tap $node.CX $node.CY | Out-Null
    Start-Sleep -Milliseconds 900
}

function Type([string]$s) {
    $esc = $s -replace '\\','\\\\' -replace '"','\\"' -replace ' ','%s' -replace '&','\&' -replace "'","\'"
    SH "input text '$esc'" | Out-Null
    Start-Sleep -Milliseconds 500
}

function Shot([string]$tag) {
    SH 'screencap -p /sdcard/s.png' | Out-Null
    & $script:ADB -s $script:DEV pull /sdcard/s.png "$script:E\$tag.png" 2>&1 | Out-Null
    SH 'rm -f /sdcard/s.png' | Out-Null
    Write-Host "[shot] $tag"
}

function Swipe([int]$x1,[int]$y1,[int]$x2,[int]$y2,[int]$ms=400) {
    & $script:ADB -s $script:DEV shell input swipe $x1 $y1 $x2 $y2 $ms | Out-Null
    Start-Sleep -Milliseconds 700
}

# Scroll the host-log list to its bottom (panel does not auto-scroll to appended rows)
function ScrollLogToEnd {
    for ($i=0; $i -lt 6; $i++) { Swipe 672 1900 672 1100 300 }
    Start-Sleep -Milliseconds 600
}
