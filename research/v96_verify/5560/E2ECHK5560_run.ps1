# E2ECHK5560 runbook helpers - device-agnostic core adapted from E2ECHK5558_run.ps1
#
# USAGE
#   . C:\...\5560\E2ECHK5560_run.ps1 -Dev emulator-5560
#   (dot-source so the functions are defined in the caller scope)
#
# ASCII ONLY: PS 5.1 parses a BOM-less .ps1 as ANSI and dies on non-ASCII punctuation.
param(
    [string]$Dev = 'emulator-5560'
)

$ErrorActionPreference = 'Stop'
$global:ADB = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v96_verify\5560'
$global:DEV = $Dev
Write-Host "E2ECHK5560 bound to device: $Dev"

function A { & $global:ADB -s $global:DEV @args }

function Shot($name) {
    $dst = Join-Path $global:DIR "$name.png"
    $out = $dst -replace '\\', '/'
    cmd /c "`"$($global:ADB)`" -s $($global:DEV) exec-out screencap -p > `"$out`""
    $len = (Get-Item $dst).Length
    if ($len -lt 20000) {
        A shell "screencap -p /sdcard/_e2e5560.png" | Out-Null
        A pull /sdcard/_e2e5560.png $dst | Out-Null
        A shell "rm -f /sdcard/_e2e5560.png" | Out-Null
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
    python (Join-Path $global:DIR 'E2ECHK5560_nodes.py') (Join-Path $global:DIR "ui_$name.xml")
}

function Rowscan($name, $y0, $y1, $mode = 'white', $thresh = 248) {
    python (Join-Path $global:DIR 'E2ECHK5560_rowscan.py') (Join-Path $global:DIR "$name.png") $y0 $y1 $mode $thresh
}

function Tap($x, $y, $ms = 700) {
    A shell "input tap $x $y" | Out-Null
    Start-Sleep -Milliseconds $ms
}

# NOTE: the brief says spaces via %s, but predecessor REPORT_pred_20261001.md section 4.1
# proved %s delivers a LITERAL "%s" into the field on this image. We use real
# KEYCODE_SPACE (62) between words instead, and never after the last word.
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

function KbShown {
    $s = (A shell "dumpsys input_method | grep mInputShown" 2>&1 | Out-String)
    if ($s -match 'mInputShown=true') { return $true } else { return $false }
}

# NEVER send ESC inside the Settings dialog: ESC dismisses the whole dialog.
# Only send it when an IME is actually up.
function HideKb {
    if (KbShown) { Esc; Write-Host 'KB hidden (was up)' }
    else { Write-Host 'KB not shown - ESC skipped' }
}

function Focus {
    (A shell "dumpsys window | grep mCurrentFocus" 2>&1 | Out-String).Trim()
}

function ResetFaillock {
    wsl -d Arch -e bash -lc "sudo faillock --user sshtest --reset; sudo faillock --user sshtest"
}

function SshdLog([string]$Since = '-3min', [int]$Tail = 25) {
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '$Since' --no-pager | tail -$Tail"
}

function SshdKeyLog {
    # accepted publickey lines only, for F12a proof
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-20min' --no-pager | grep -Ei 'Accepted publickey|Accepted password|Failed|authentication failure' | tail -30"
}
