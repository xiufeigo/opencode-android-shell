# E2ECHK5558_run.ps1 - shared helper library for the v97 hanging-items re-verify batch.
# Target device: emulator-5558 = Pixel_10_Pro_XL (AssertDevice re-checks it; the
# serial-to-AVD mapping was re-shuffled, so NEVER trust the serial).
#
# ASCII ONLY: PS 5.1 parses a BOM-less .ps1 as ANSI and dies on non-ASCII punctuation.
#
# Discipline baked into this library:
#   - NO gradle, NO install, NO force-stop / kill, nothing on 127.0.0.1:5555.
#     LaunchApp only ever issues `am start -n` (bring-to-front), never force-stop.
#   - NO keyevent 4 (BACK) anywhere.
#   - NO keyevent 111 (ESC) while a Settings dialog/overlay is open. To dismiss the
#     keyboard use keyevent 61 (TAB).
#   - Screenshots: screencap -p /sdcard/... + adb pull. NEVER exec-out.
#   - Coordinates are resolved from uiautomator dumps at runtime; nothing hardcoded.
#   - input text NEVER carries a literal trailing space (it is dropped before the app
#     ever sees it, same in the system Settings EditText). Spaces are keyevent 62.
#     %s is NOT used (this image once delivered a literal "%s").
param(
    [string]$Dev = 'emulator-5558'
)
$ErrorActionPreference = 'Stop'
$global:ADB  = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$global:DIR  = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5558'
$global:DEV  = $Dev
$global:PKG  = 'com.xiufeigo.opencodeshell'
$global:PY   = (Get-Command python).Source
Write-Host "E2ECHK5558 bound to device: $Dev  evidence dir: $global:DIR"

# ---------- basic adb plumbing ----------
function A { & $global:ADB -s $global:DEV @args }

# Monotonic host clock, ms since process start. Used for every timing assertion.
$global:T0 = [Diagnostics.Stopwatch]::StartNew()
function Ms { return [int]$global:T0.ElapsedMilliseconds }
function Stamp { return ('{0,7}ms' -f (Ms)) }

# Screenshot via /sdcard + pull. Discipline: never exec-out.
function Shot($name) {
    $dst = Join-Path $global:DIR "$name.png"
    A shell "screencap -p /sdcard/_e2echk5558.png" | Out-Null
    A pull /sdcard/_e2echk5558.png $dst | Out-Null
    A shell "rm -f /sdcard/_e2echk5558.png" | Out-Null
    $len = (Get-Item $dst).Length
    if ($len -lt 20000) { Write-Host "WARN $name.png only $len bytes - inspect before trusting" }
    Write-Host "$(Stamp) SHOT $name.png bytes=$len"
    return $dst
}

# Close a Compose Popup by tapping its scrim.  The popup is its OWN window, so
# uiautomator only reports the popup's own nodes - the host screen's affordances
# are invisible while it is up.  The tap point is DERIVED from the popup's own
# bounds (leftmost content node, minus a margin, at the content's vertical
# centre), never hardcoded.  BACK is forbidden, so this is the only way out.
function ClosePopup($name) {
    $n = Nodes $name
    $b = @()
    foreach ($line in $n) {
        $x = BoundsOf $line
        if ($x) { $b += ,$x }
    }
    if ($b.Count -eq 0) { return $false }
    $minX = ($b | ForEach-Object { $_[0] } | Measure-Object -Minimum).Minimum
    $maxY = ($b | ForEach-Object { $_[3] } | Measure-Object -Maximum).Maximum
    $minY = ($b | ForEach-Object { $_[1] } | Measure-Object -Minimum).Minimum
    $x = [Math]::Max(40, [int]$minX - 200)
    $y = [int](($minY + $maxY) / 2)
    Write-Host "$(Stamp) SCRIM-TAP $x,$y (popup content x>=$minX)"
    A shell "input tap $x $y" | Out-Null
    Start-Sleep -Milliseconds 900
    return $true
}

# uiautomator dump + pull -> ui_<name>.xml in the evidence dir.
function Ui($name) {
    $ErrorActionPreference = 'Continue'
    A shell "uiautomator dump /sdcard/_e2echk5558.xml" 2>&1 | Out-Null
    A pull "/sdcard/_e2echk5558.xml" (Join-Path $global:DIR "ui_$name.xml") 2>&1 | Out-Null
    A shell "rm -f /sdcard/_e2echk5558.xml" 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
    Write-Host "$(Stamp) UI ui_$name.xml"
    return (Join-Path $global:DIR "ui_$name.xml")
}

# Node table via python helper.
function Nodes($name) {
    $p = Ui $name
    return (& $global:PY (Join-Path $global:DIR 'E2ECHK5558_nodes.py') $p)
}

function Has($nodes, [string]$pat) { return [bool]($nodes | Select-String -Pattern $pat -Quiet) }

# Centre of a "[x1,y1][x2,y2]" bounds pair. ALWAYS cast to int first.
function CenterOf($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
        $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}

# Raw bounds of a "[x1,y1][x2,y2]" string -> @x1,y1,x2,y2
function BoundsOf($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        return ,@([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    }
    return $null
}

function Tap($x, $y, $ms = 700) {
    Write-Host "$(Stamp) TAP $x,$y"
    A shell "input tap $x $y" | Out-Null
    Start-Sleep -Milliseconds $ms
}

# Dump fresh, tap the Nth node line matching $pat. Coordinates never hardcoded.
function TapMatch([string]$pat, [string]$dump = 'tap_tmp', [int]$index = 0) {
    $n = Nodes $dump
    $m = @($n | Select-String -Pattern $pat)
    if ($m.Count -le $index) {
        Write-Host "NOMATCH '$pat' ($($m.Count) hit(s)); visible text nodes:"
        ($n | Select-String -Pattern 'EditText|TextView|Button') | Select-Object -First 16 |
            ForEach-Object { Write-Host "   $($_.Line)" }
        throw "TapMatch: no node matches '$pat'"
    }
    $p = CenterOf $m[$index].Line
    if (-not $p) { throw "TapMatch: node has no bounds: $($m[$index].Line)" }
    Write-Host "$(Stamp) TAPMATCH '$pat' #${index} @ $($p[0]),$($p[1])  <<$(($m[$index].Line -split "`t")[0])>>"
    Tap $p[0] $p[1]
    return $m[$index].Line
}

# ASCII text into the focused field. Segments <= 8 chars, sleep between segments.
# Spaces between words are ALWAYS keyevent 62 (a literal space inside `input text`
# is dropped by the platform before the app sees it). Never a literal trailing space.
function TypeText($t, [int]$chunk = 8, [int]$sleepMs = 1200) {
    foreach ($ch in $t.ToCharArray()) {
        if ($ch -eq ' ') {
            A shell "input keyevent 62" | Out-Null
            Start-Sleep -Milliseconds 450
            continue
        }
        $s = [string]$ch
        A shell "input text '$s'" | Out-Null
        Start-Sleep -Milliseconds $sleepMs
    }
}

# Type a word, then one real KEYCODE_SPACE, then a word. For the v75 whitespace case.
function TypeWord([string]$w, [int]$sleepMs = 1200) {
    for ($i = 0; $i -lt $w.Length; $i += 8) {
        $seg = $w.Substring($i, [Math]::Min(8, $w.Length - $i))
        A shell "input text '$seg'" | Out-Null
        Start-Sleep -Milliseconds $sleepMs
    }
}
function Space([int]$ms = 500) {
    Write-Host "$(Stamp) SPACE (keyevent 62)"
    A shell "input keyevent 62" | Out-Null
    Start-Sleep -Milliseconds $ms
}

# Type a PHRASE: <=8 char segments with a sleep between them, a real
# KEYCODE_SPACE (62) between words, never a literal space, never %s.  Used for
# prompts where the app must receive the exact bytes.
function TypePhrase($t, [int]$chunk = 8, [int]$sleepMs = 900) {
    $words = @($t -split ' ' | Where-Object { $_ -ne '' })
    for ($w = 0; $w -lt $words.Count; $w++) {
        $word = $words[$w]
        for ($i = 0; $i -lt $word.Length; $i += $chunk) {
            $seg = $word.Substring($i, [Math]::Min($chunk, $word.Length - $i))
            A shell "input text '$seg'" | Out-Null
            Start-Sleep -Milliseconds $sleepMs
        }
        if ($w -lt $words.Count - 1) {
            A shell "input keyevent 62" | Out-Null
            Start-Sleep -Milliseconds 450
        }
    }
    Write-Host "$(Stamp) TYPED '$t'"
}

# Collapse the keyboard with TAB (never BACK, never ESC).
function TabKey([int]$ms = 700) {
    Write-Host "$(Stamp) TAB (keyevent 61, keyboard collapse)"
    A shell "input keyevent 61" | Out-Null
    Start-Sleep -Milliseconds $ms
}

# ---------- device-side state ----------
function AssertDevice {
    $name = (A emu avd name) 2>$null | Select-Object -First 1
    if ("$name".Trim() -ne 'Pixel_10_Pro_XL') { throw "device $global:DEV is '$name', expected Pixel_10_Pro_XL - STOP" }
    Write-Host "DEVICE OK: $global:DEV = Pixel_10_Pro_XL"
}

function AppVersion {
    (A shell "dumpsys package $global:PKG" | Out-String) -split "`n" |
        Select-String -Pattern 'versionName|versionCode|lastUpdateTime|firstInstallTime' |
        ForEach-Object { $_.ToString().Trim() }
}

function AppRunning {
    return ((A shell "ps -A | grep $global:PKG" | Out-String).Trim().Length -gt 0)
}

# Bring the app to the front. NEVER force-stop (process killing is forbidden).
function LaunchApp {
    A shell "am start -n $global:PKG/.OcActivity" | Out-Null
    Start-Sleep -Seconds 6
    return ((A shell "dumpsys window | grep mCurrentFocus" | Out-String).Trim())
}

function ScreenSize { A shell "wm size" | Out-String }
function Density { A shell "wm density" | Out-String }

# ---------- logcat (read-only; never clears, never kills) ----------
function Log([string]$Tag = 'dump', [int]$Tail = 200) {
    $out = Join-Path $global:DIR "log_$Tag.txt"
    cmd /c "`"$($global:ADB)`" -s $($global:DEV) logcat -d -t 4000 > `"$out`" 2>&1" | Out-Null
    Write-Host "$(Stamp) LOG $out"
    return $out
}
function LogHit([string]$Tag, [string]$pat, [int]$Tail = 60) {
    $p = Log $Tag
    (Get-Content $p | Select-String -Pattern $pat | Select-Object -Last $Tail) | ForEach-Object { Write-Host "   $_" }
}

# ---------- backend ----------
$global:BASE = 'http://127.0.0.1:4095'
function ApiGet([string]$path) {
    $u = "$global:BASE$path"
    return (curl.exe -s -u opencode:opencode $u)
}
function ApiGetFile([string]$path, [string]$name) {
    $u = "$global:BASE$path"
    $out = Join-Path $global:DIR $name
    curl.exe -s -u opencode:opencode $u -o $out
    Write-Host "$(Stamp) API GET $path -> $name"
    return $out
}
function ApiInfo { return (ApiGet '/api/info') }

# Locate my own sessions by title prefix (never touches anyone else's session).
# /api/session returns {"data":[ ... ]}.
function SessionList {
    $raw = ApiGet '/api/session'
    return ($raw | ConvertFrom-Json).data
}
function MySessions([string]$prefix = 'E2ECHK5558') {
    $out = @()
    foreach ($s in (SessionList)) {
        if ($s.title -and $s.title.StartsWith($prefix)) { $out += $s }
    }
    return $out
}
# Messages of ONE session, saved to evidence dir; returns the json object.
function SessionMessages([string]$sid, [string]$name) {
    $out = Join-Path $global:DIR $name
    curl.exe -s -u opencode:opencode "$global:BASE/api/session/$sid/message" -o $out
    Write-Host "$(Stamp) API GET /api/session/$sid/message -> $name"
    return (Get-Content $out -Raw)
}
