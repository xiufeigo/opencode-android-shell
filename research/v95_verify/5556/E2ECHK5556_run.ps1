# E2ECHK5556 - runbook helpers bound to emulator-5556 = Pixel_9a (1080x2424@420dpi)
#
# Derived from 5558/E2ECHK5558_run.ps1 (device-agnostic there, hardcoded 5558 here is
# a 5556-only copy). Every adb call goes through $global:DEV; no hardcoded serial
# anywhere except this file's $Dev default.
#
# USAGE  (dot-source so functions land in caller scope):
#   . C:\sensorsdata\main\program\opencode-android-shell\research\v95_verify\5556\E2ECHK5556_run.ps1
#
# ASCII ONLY: PS 5.1 parses a BOM-less .ps1 as ANSI and dies on non-ASCII punctuation.
param(
    [string]$Dev = 'emulator-5556'
)

$ErrorActionPreference = 'Stop'
$global:ADB = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v95_verify\5556'
$global:DEV = $Dev
$global:PFX = 'E2ECHK5556_V95C'   # my evidence prefix (avoids the 0-110 legacy set)

# Sanity: prove we are on the right AVD before touching anything.
$avd = (& $global:ADB -s $global:DEV emu avd name 2>&1 | Out-String).Trim()
$size = (& $global:ADB -s $global:DEV shell wm size 2>&1 | Out-String).Trim()
Write-Host "BOUND dev=$Dev avd=$avd"
if ($avd -notmatch 'Pixel_9a') { throw "WRONG DEVICE: avd name is '$avd', expected Pixel_9a. ABORT." }

# adb writes progress/warnings to stderr, which PS raises as NativeCommandError under
# $ErrorActionPreference='Stop'. Drop to Continue for the duration of the call.
function A {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $global:ADB -s $global:DEV @args 2>&1
    $ErrorActionPreference = $prev
}

function Shot($name) {
    $dst = Join-Path $global:DIR "$name.png"
    $out = $dst -replace '\\', '/'
    cmd /c "`"$($global:ADB)`" -s $($global:DEV) exec-out screencap -p > `"$out`""
    $len = (Get-Item $dst).Length
    if ($len -lt 20000) {
        A shell "screencap -p /sdcard/_e2e5556.png" | Out-Null
        A pull /sdcard/_e2e5556.png $dst | Out-Null
        A shell "rm -f /sdcard/_e2e5556.png" | Out-Null
        $len = (Get-Item $dst).Length
    }
    Write-Host "SHOT $name.png bytes=$len"
}

function Ui($name) {
    $ErrorActionPreference = 'Continue'
    A shell "uiautomator dump /sdcard/$name.xml" 2>&1 | Out-Null
    A pull "/sdcard/$name.xml" (Join-Path $global:DIR "ui_$name.xml") 2>&1 | Out-Null
    A shell "rm -f /sdcard/$name.xml" 2>&1 | Out-Null
    Write-Host "UI ui_$name.xml"
}

function Nodes($name) {
    Ui $name
    python (Join-Path $global:DIR 'E2ECHK5556_nodes.py') (Join-Path $global:DIR "ui_$name.xml")
}

# Find a node by regex over its text; print "text<TAB>bounds<TAB>pw<TAB>class<TAB>foc"
function Find($name, $pat) {
    Ui $name
    $p = (Join-Path $global:DIR "ui_$name.xml")
    Select-String -Path $p -Pattern "text=`"([^`"]*$pat[^`"]*)`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"" -AllMatches |
        ForEach-Object { $_.Matches } |
        ForEach-Object {
            $t = $_.Groups[1].Value
            $x1 = [int]$_.Groups[2].Value; $y1 = [int]$_.Groups[3].Value
            $x2 = [int]$_.Groups[4].Value; $y2 = [int]$_.Groups[5].Value
            $cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)
            "{0,-34} [{1},{2}][{3},{4}]  tap=({5},{6})" -f $t, $x1, $y1, $x2, $y2, $cx, $cy
        }
}

function Tap($x, $y, $ms = 700) {
    A shell "input tap $x $y" | Out-Null
    Start-Sleep -Milliseconds $ms
}

# `input text` %s is unreliable here (literal %s can reach the app and the target is
# then parsed as a remote command). Emit real SPACE keyevents between words, never
# after the last word (that would leave a trailing space).
function TypeText($t, $chunk = 8, $sleepMs = 1500) {
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
            Start-Sleep -Milliseconds 400
        }
    }
}

function Esc { A shell "input keyevent 111" | Out-Null; Start-Sleep -Milliseconds 600 }

# mInputShown lives in `dumpsys input_method` on this build, not `dumpsys window`.
# NEVER send ESC inside the Settings dialog: ESC dismisses the whole dialog.
function KbUp {
    $s = (A shell "dumpsys input_method | grep mInputShown" 2>&1 | Out-String)
    if ($s -match 'mInputShown=true') { 'UP' } else { 'DOWN' }
}

function HideKb {
    if ((KbUp) -eq 'UP') { Esc; Start-Sleep -Milliseconds 500; Write-Host 'KB hidden (was up)' }
    else { Write-Host 'KB not shown - ESC skipped' }
}

function Focus {
    (A shell "dumpsys window | grep mCurrentFocus" 2>&1 | Out-String).Trim()
}

# Pixel row scan: measures Gboard top edge / footer clearance.
function Rowscan($name, $y0, $y1, $mode = 'white', $thresh = 248) {
    python (Join-Path $global:DIR 'E2ECHK5556_rowscan.py') (Join-Path $global:DIR "$name.png") $y0 $y1 $mode $thresh
}

# pam_faillock locks sshtest after repeated bad passwords. Run BEFORE a wrong-password test.
function ResetFaillock {
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    wsl -d Arch -e bash -lc "sudo faillock --user sshtest --reset; sudo faillock --user sshtest" 2>&1
    $ErrorActionPreference = $prev
}

# WSL prints a localhost-proxy notice on stderr, which PS escalates under
# ErrorActionPreference='Stop'. Tolerate it, write the log to a file, echo the file.
function SshdLog($tag = 'now') {
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $f = Join-Path $global:DIR "E2ECHK5556_V95C_sshd_$tag.txt"
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-10min' --no-pager | grep -Ei 'sshtest|Accepted|Failed|invalid|Connection|session opened|session closed'" 2>&1 |
        Where-Object { $_ -notmatch '^\s*$' } | Out-File -FilePath $f -Encoding utf8
    $ErrorActionPreference = $prev
    Get-Content $f
}
