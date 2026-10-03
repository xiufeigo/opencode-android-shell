# E2ECHK5558 runbook helpers - device-agnostic (fixes predecessor hardcoded-device bug)
#
# WHY THIS FILE EXISTS
#   The predecessor's E2ECHK5558_nav.ps1 Shot() hardcoded `-s emulator-5558` while every
#   other helper used $global:DEV. Setting $env:E2E_DEV therefore captured screenshots
#   from the WRONG device. Here every adb call goes through $Dev, with no hardcoded serial.
#
# USAGE
#   . C:\...\5558\E2ECHK5558_run.ps1 -Dev emulator-5556
#   (dot-source so the functions are defined in the caller scope)
#
# ASCII ONLY: PS 5.1 parses a BOM-less .ps1 as ANSI and dies on non-ASCII punctuation.
param(
    [string]$Dev = $(if ($env:E2E_DEV) { $env:E2E_DEV } else { 'emulator-5558' })
)

$ErrorActionPreference = 'Stop'
$global:ADB = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v95_verify\5558'
$global:DEV = $Dev
Write-Host "E2ECHK5558 bound to device: $Dev"

function A { & $global:ADB -s $global:DEV @args }

function Shot($name) {
    $dst = Join-Path $global:DIR "$name.png"
    $out = $dst -replace '\\', '/'
    cmd /c "`"$($global:ADB)`" -s $($global:DEV) exec-out screencap -p > `"$out`""
    $len = (Get-Item $dst).Length
    if ($len -lt 20000) {
        A shell "screencap -p /sdcard/_e2e5558.png" | Out-Null
        A pull /sdcard/_e2e5558.png $dst | Out-Null
        A shell "rm -f /sdcard/_e2e5558.png" | Out-Null
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

# Dump nodes as: text \t bounds \t pw= \t class \t focused=
function Nodes($name) {
    Ui $name
    $x = Join-Path $global:DIR "ui_$name.xml"
    python (Join-Path $global:DIR 'E2ECHK5558_nodes.py') $x
}

# Pixel row scan (measures Gboard top edge / footer clearance). See E2ECHK5558_rowscan.py
function Rowscan($name, $y0, $y1, $mode = 'white', $thresh = 248) {
    python (Join-Path $global:DIR 'E2ECHK5558_rowscan.py') (Join-Path $global:DIR "$name.png") $y0 $y1 $mode $thresh
}

function Tap($x, $y, $ms = 700) {
    A shell "input tap $x $y" | Out-Null
    Start-Sleep -Milliseconds $ms
}

# `input text` with %s is unreliable here: a literal "%s" can reach the app and the
# target is then parsed as a remote command. Emit real SPACE keyevents between words,
# and never after the last word (that would leave a trailing space).
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
# NEVER send ESC inside the Settings dialog to hide the keyboard: ESC dismisses the
# whole dialog. Only send it when an IME is actually up.
function HideKb {
    $s = (A shell "dumpsys input_method | grep mInputShown" 2>&1 | Out-String)
    if ($s -match 'mInputShown=true') { Esc; Write-Host 'KB hidden (was up)' }
    else { Write-Host 'KB not shown - ESC skipped' }
}

function Focus {
    (A shell "dumpsys window | grep mCurrentFocus" 2>&1 | Out-String).Trim()
}

# ---------------------------------------------------------------------------
# COMPACT auth answer - the SSH auth session has a server-side timeout, so
# 'card appears -> focus password -> type -> Continue' must run as one tight
# script, never as slow manual steps.
#
# Pixel_9a 1080x2424@420 geometry, measured from the predecessor's uiautomator dumps:
#   keyboard HIDDEN : field [138,1578][943,1704] c=(540,1641)
#                     Cancel [588,1742][699,1783] c=(643,1762)
#                     Continue [794,1742][938,1783] c=(866,1762)
#   keyboard SHOWN  : field [138,1093][943,1219] c=(540,1156)
#                     Cancel [588,1327][699,1368] c=(643,1347)
#                     Continue [794,1327][938,1368] c=(866,1347)
# Re-verify with Nodes() on every run; do not trust these blindly.
# ---------------------------------------------------------------------------
function AnswerCard($secret, $expectKeyboard = $true) {
    if ($expectKeyboard) {
        Tap 540 1641 500          # focus password field -> IME comes up, card shifts up
        Start-Sleep -Milliseconds 900
    }
    Shot 'E2ECHK5558_card_kbfocus'
    Rowscan 'E2ECHK5558_card_kbfocus' 1250 1600
    TypeText $secret
    Tap 866 1347 300            # Continue while the card is still in its keyboard-up position
    Start-Sleep -Milliseconds 1200
    Shot 'E2ECHK5558_card_continued'
    Focus
}

# Cancel must dismiss the card and NOT immediately re-offer the SAME prompt.
# Call this on a fresh challenge, then sleep and re-shot to prove no re-prompt.
function CancelCard($expectKeyboard = $false) {
    if ($expectKeyboard) { Tap 540 1641 500; Start-Sleep -Milliseconds 900 }
    Tap 643 1347 400            # Cancel (keyboard-up position)
    Start-Sleep -Milliseconds 1500
    Shot 'E2ECHK5558_after_cancel_t0'
    Start-Sleep -Seconds 6      # offered-once window: card must NOT come back
    Shot 'E2ECHK5558_after_cancel_t6'
    Nodes 'E2ECHK5558_after_cancel_t6'
}

# pam_faillock locks sshtest after repeated bad passwords. Run this FIRST if the
# server starts refusing every password, or BEFORE a wrong-password test entry.
function ResetFaillock {
    wsl -d Arch -e bash -lc "sudo faillock --user sshtest --reset; sudo faillock --user sshtest"
}

function SshdLog {
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-10min' --no-pager | tail -25"
}
