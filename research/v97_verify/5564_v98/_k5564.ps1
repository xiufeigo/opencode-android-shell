# _k5564.ps1 - local helper for the 5564 P3 re-verification.
# Discipline: no keyevent 4 (BACK), no keyevent 111 (ESC) inside dialogs,
# screenshots only via `screencap -p /sdcard/...` + `adb pull`,
# coordinates always parsed from a uiautomator dump at runtime.
#
# NOTE: Chinese literals typed on the command line get mangled by the console
# codepage, so node selection is INDEX based (ListNodes / TapIdx), never by
# typing a Chinese pattern in the shell.
$ErrorActionPreference = 'Stop'
$script:ADB = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$script:DEV = "emulator-5564"
$script:PKG = "com.xiufeigo.opencodeshell"
$script:DIR = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5564_v98"

function A { & $script:ADB -s $script:DEV @args }

function Shot($name) {
    A shell "screencap -p /sdcard/_k5564.png" | Out-Null
    A pull /sdcard/_k5564.png (Join-Path $script:DIR "$name.png") | Out-Null
    A shell "rm -f /sdcard/_k5564.png" | Out-Null
    $len = (Get-Item (Join-Path $script:DIR "$name.png")).Length
    Write-Host "SHOT $name.png bytes=$len"
}

function Ui($name) {
    $dst = Join-Path $script:DIR "ui_$name.xml"
    for ($try = 1; $try -le 5; $try++) {
        A shell "uiautomator dump /sdcard/_k5564.xml" 2>&1 | Out-Null
        A pull "/sdcard/_k5564.xml" $dst 2>&1 | Out-Null
        if (Test-Path $dst) { A shell "rm -f /sdcard/_k5564.xml" | Out-Null; return $dst }
        Start-Sleep -Milliseconds 900
    }
    Write-Host "UIDUMP FAILED after 5 tries ($name)"
    return $dst
}

# Parse every node carrying a non-empty text or content-desc. Handles BOTH
# text="..." and text='...' quoting. Returns objects with index/X/Y.
function ParseNodes($xml) {
    $t = Get-Content $xml -Raw -Encoding UTF8
    $out = New-Object System.Collections.ArrayList
    $i = 0
    # Split on node open tags; each chunk carries that node's own attributes.
    foreach ($m in ([regex]'<node\s').Matches($t)) { }
    $chunks = $t -split '<node\s+'
    for ($ci = 1; $ci -lt $chunks.Count; $ci++) {
        $whole = $chunks[$ci]
        $cut = $whole.IndexOf('>')
        if ($cut -ge 0) { $whole = $whole.Substring(0, $cut) }
        $val = ''
        foreach ($attr in @('text', 'content-desc')) {
            if ($val -ne '') { break }
            $am = [regex]::Match($whole, "\b$attr\s*=\s*`"([^`"]*)`"")
            if (-not $am.Success) { $am = [regex]::Match($whole, "\b$attr\s*=\s*'([^']*)'") }
            if ($am.Success -and $am.Groups[1].Value -ne '') { $val = $am.Groups[1].Value }
        }
        if ($val -eq '') { continue }
        $b = [regex]::Match($whole, 'bounds\s*=\s*"\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $x1 = [int]$b.Groups[1].Value; $y1 = [int]$b.Groups[2].Value
        $x2 = [int]$b.Groups[3].Value; $y2 = [int]$b.Groups[4].Value
        [void]$out.Add([pscustomobject]@{
            I = $i; Text = $val
            X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2)
            Bounds = $b.Value; C = $x2 - $x1; R = $y2 - $y1
        })
        $i++
    }
    return $out
}

# Print an indexed table of nodes so the operator can pick by number.
function ListNodes($xml, $tag) {
    Write-Host "--- nodes of $tag ---"
    foreach ($n in (ParseNodes $xml)) {
        Write-Host ("[{0,2}] {1,-46} c={2,4} r={3,4} @ {4},{5}" -f $n.I, $n.Text, $n.C, $n.R, $n.X, $n.Y)
    }
}

function Tap($x, $y) { A shell "input tap $x $y" | Out-Null; Start-Sleep -Milliseconds 800 }

function TapIdx($xml, $idx, $ms = 800) {
    $nodes = ParseNodes $xml
    $n = $nodes | Where-Object { $_.I -eq $idx }
    if (-not $n) { Write-Host "MISS index $idx"; return $false }
    Write-Host "TAP   [$idx] '$($n.Text)' @ $($n.X),$($n.Y)"
    A shell "input tap $($n.X) $($n.Y)" | Out-Null
    Start-Sleep -Milliseconds $ms
    return $true
}

# Tap the node at $idx then type $text (ASCII-only payloads).
function TypeIdx($xml, $idx, $text) {
    $nodes = ParseNodes $xml
    $n = $nodes | Where-Object { $_.I -eq $idx }
    if (-not $n) { Write-Host "MISS index $idx"; return $false }
    Write-Host "TYPE  [$idx] '$($n.Text)' -> '$text' @ $($n.X),$($n.Y)"
    A shell "input tap $($n.X) $($n.Y)" | Out-Null
    Start-Sleep -Milliseconds 500
    A shell "input text $text" | Out-Null
    Start-Sleep -Milliseconds 400
    return $true
}

function Focus { (A shell "dumpsys window | grep -E 'mCurrentFocus'" | Out-String).Trim() }
function LogcatClear { A logcat -c | Out-Null }
function LogcatDump($name) {
    $p = Join-Path $script:DIR "log_$name.txt"
    cmd /c "$($script:ADB) -s $($script:DEV) logcat -d -v threadtime > `"$p`" 2>&1"
    Write-Host "LOG   log_$name.txt ($((Get-Item $p).Length) bytes)"
    return $p
}
function SshdAccepted($since) {
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '$since' --no-pager | grep -E 'Accepted|Failed|Connection closed|error' | tail -25"
}
