# E2ECHK5556_lib.ps1 - shared helper library for the v97 v82 five-criteria round on emulator-5556.
# Device: emulator-5556 = Medium_Desktop, 1366x768@160dpi (verified 2026-10-02 10:03).
#
# ASCII ONLY: PS 5.1 parses a BOM-less .ps1 as ANSI and dies on non-ASCII punctuation.
#
# Discipline baked into this library:
#   - NO keyevent 4 (BACK) anywhere. IME collapse uses keyevent 61 (TAB).
#   - NO keyevent 111 (ESC) while a Settings dialog/overlay is open.
#   - Screenshots: screencap -p /sdcard/... + adb pull. NEVER exec-out.
#   - NO gradle, NO install, NO process kill, never touch 127.0.0.1:5555.
#   - Coordinates resolved from uiautomator dumps at runtime; no hardcoded taps.
param(
    [string]$Dev = 'emulator-5556'
)
$ErrorActionPreference = 'Stop'
$global:ADB = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v82'
$global:DEV = $Dev
$global:PKG = 'com.xiufeigo.opencodeshell'
Write-Host "E2ECHK5556 bound to device: $Dev  evidence dir: $global:DIR"

# ---------- basic adb plumbing ----------
function A { & $global:ADB -s $global:DEV @args }

# Screenshot via /sdcard + pull (discipline: no exec-out).
function Shot($name) {
    $dst = Join-Path $global:DIR "$name.png"
    A shell "screencap -p /sdcard/_e2echk5556.png" | Out-Null
    A pull /sdcard/_e2echk5556.png $dst | Out-Null
    A shell "rm -f /sdcard/_e2echk5556.png" | Out-Null
    $len = (Get-Item $dst).Length
    if ($len -lt 20000) { Write-Host "WARN $name.png only $len bytes - inspect before trusting" }
    Write-Host "SHOT $name.png bytes=$len"
}

# uiautomator dump + pull. Result: ui_<name>.xml in the evidence dir.
function Ui($name) {
    $ErrorActionPreference = 'Continue'
    A shell "uiautomator dump /sdcard/_e2echk5556.xml" 2>&1 | Out-Null
    A pull "/sdcard/_e2echk5556.xml" (Join-Path $global:DIR "ui_$name.xml") 2>&1 | Out-Null
    A shell "rm -f /sdcard/_e2echk5556.xml" 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
    Write-Host "UI ui_$name.xml"
}

# Node table (text/content-desc + bounds) via python helper.
function Nodes($name) {
    Ui $name
    python (Join-Path $global:DIR 'E2ECHK5556_nodes.py') (Join-Path $global:DIR "ui_$name.xml")
}

function Has($nodes, [string]$pat) { [bool]($nodes | Select-String -Pattern $pat -Quiet) }

# Centre of a "[x1,y1][x2,y2]" bounds pair. ALWAYS cast to int first.
function CenterOf($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
        $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return , @([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}

# Extract raw numbers from the Nth bounds match in a line: Y1, Y2, X1, X2
function BoundsOf($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        return , @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    }
    return $null
}

function Tap($x, $y, $ms = 700) {
    A shell "input tap $x $y" | Out-Null
    Start-Sleep -Milliseconds $ms
}

# Dump fresh, tap the Nth node line matching $pat. Returns the matched line.
function TapMatch([string]$pat, [string]$dump = 'tmp_tap', [int]$index = 0) {
    $n = Nodes $dump
    $m = @($n | Select-String -Pattern $pat)
    if ($m.Count -le $index) {
        Write-Host "NOMATCH '$pat' ($($m.Count) hit(s)); visible text nodes:"
        ($n | Select-String -Pattern 'EditText|TextView|Button') | Select-Object -First 20 |
            ForEach-Object { Write-Host "   $($_.Line)" }
        return $null
    }
    $line = $m[$index].Line
    $c = CenterOf $line
    if ($null -eq $c) { Write-Host "NOBOUNDS '$pat' -> $line"; return $null }
    Write-Host "TAP '$pat' idx=$index at ($($c[0]),$($c[1])) from: $line"
    Tap $c[0] $c[1]
    return $line
}

# Log a line into the running evidence log.
function Log($msg) {
    $ts = (Get-Date).ToString('HH:mm:ss')
    $line = "[$ts] $msg"
    Add-Content -Path (Join-Path $global:DIR 'E2ECHK5556_run.log') -Value $line
    Write-Host $line
}

# App launch (OcActivity is the launcher; MainActivity is not exported).
function LaunchApp {
    A shell "am start -n com.xiufeigo.opencodeshell/.OcActivity" | Out-Null
    Start-Sleep -Seconds 3
    Log "launched com.xiufeigo.opencodeshell/.OcActivity"
}

# Kill-and-relaunch is FORBIDDEN in this round. Only am start.
function AssertNoProcessKill {}
