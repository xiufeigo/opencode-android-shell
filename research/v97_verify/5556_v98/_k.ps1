# _k.ps1 - D3-4 helper for emulator-5556. Runtime coordinate parsing only, no hardcoded taps.
$global:DEV = 'emulator-5556'
$global:PKG = 'com.xiufeigo.opencodeshell'
$global:W   = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98'
$env:PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:PATH"

function A { param([string]$c) adb -s $global:DEV shell $c 2>&1 }

# Device-ownership gate: top window must be our app. Non-app => BLOCKED.
function AssertDevice {
    $f = (A "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
    $txt = ($f | Out-String)
    if ($txt -notmatch [regex]::Escape($global:PKG)) {
        Write-Host "BLOCKED: top window is NOT our app."
        Write-Host $txt
        throw "Device ownership lost - STOP"
    }
    if ($txt -match 'Application Not Responding' -or $txt -match 'ANR') {
        Write-Host "ANR detected (our app). reboot authorized."
        return 'ANR'
    }
    if ($txt -match 'coderemote') { throw "coderemote present - STOP" }
    return 'OK'
}

# Parse all nodes; match BOTH text="..." and text='...'
function ParseNodes {
    param([string]$xml)
    $out = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
        $v = $m.Value
        $n = @{}
        foreach ($p in @('text','content-desc','class','package','resource-id','bounds','clickable','enabled','scrollable','selected','index','checkable','checked')) {
            if ($v -match ($p + '="([^"]*)"')) { $n[$p] = $Matches[1] }
            elseif ($v -match ($p + "='([^']*)'")) { $n[$p] = $Matches[1] }
        }
        if ($n.ContainsKey('bounds') -and $n['bounds'] -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $bx1=[int]$Matches[1]; $by1=[int]$Matches[2]; $bx2=[int]$Matches[3]; $by2=[int]$Matches[4]
            $n['x1']=$bx1; $n['y1']=$by1; $n['x2']=$bx2; $n['y2']=$by2
            $n['cx']=[int](($bx1+$bx2)/2); $n['cy']=[int](($by1+$by2)/2)
        }
        $out += [pscustomobject]$n
    }
    return $out
}

function Dump {
    param([string]$tag = 'x')
    A "uiautomator dump --compressed /sdcard/_k.xml" | Out-Null
    A "cat /sdcard/_k.xml" | Out-String
}

function Shot {
    param([string]$name)
    A "screencap -p /sdcard/_k.png" | Out-Null
    adb -s $global:DEV pull /sdcard/_k.png "$global:W\$name.png" 2>&1 | Out-Null
    A "rm -f /sdcard/_k.png" | Out-Null
}

# Find a node by text (or content-desc). Exact first, then contains. Returns first match.
function Find {
    param([string]$q, [string]$kind = 'text')
    $nodes = ParseNodes (Dump)
    $key = if ($kind -eq 'desc') { 'content-desc' } else { 'text' }
    $r = $nodes | Where-Object { $_.$key -eq $q } | Select-Object -First 1
    if (-not $r) { $r = $nodes | Where-Object { $_.$key -and $_.$key.Contains($q) } | Select-Object -First 1 }
    return $r
}

function TapNode {
    param([string]$q, [string]$kind = 'text', [int]$n = 1)
    $nodes = ParseNodes (Dump)
    $key = if ($kind -eq 'desc') { 'content-desc' } else { 'text' }
    $hits = @($nodes | Where-Object { $_.$key -eq $q })
    if ($hits.Count -lt $n) { $hits = @($nodes | Where-Object { $_.$key -and $_.$key.Contains($q) }) }
    if ($hits.Count -lt $n) { Write-Host "TAP-FAIL: '$q' (#$n) not found"; return $false }
    $h = $hits[$n-1]
    Write-Host "  tap '$($h.$key)' @ $($h.cx),$($h.cy)  [$($h.bounds)] clickable=$($h.clickable)"
    A "input tap $($h.cx) $($h.cy)" | Out-Null
    Start-Sleep -Milliseconds 900
    return $true
}

function TypeText {
    param([string]$t)
    $safe = $t -replace ' ','%s' -replace "'","'\\''" -replace '"','\\"'
    A "input text '$safe'" | Out-Null
    Start-Sleep -Milliseconds 400
}

function Swipe {
    param([int]$x1,$y1,$x2,$y2,[int]$ms = 400)
    A "input swipe $x1 $y1 $x2 $y2 $ms" | Out-Null
    Start-Sleep -Milliseconds 700
}

# Print the visible text inventory (ordered by y then x) - the row manifest
function ShowRows {
    $nodes = ParseNodes (Dump)
    $nodes | Where-Object { $_.text -and $_.text -ne '' } |
        Sort-Object y1, x1 |
        ForEach-Object { "  y=$($_.y1)-$($_.y2) x=$($_.x1) '$($_.text)'" }
}
