# f14lib.ps1 - F14 final-acceptance UI helpers for emulator-5554 (Pixel_9a 1080x2424@420).
#
# CORE DISCOVERY (v98, this round): in the Add SSH server form the uiautomator dump
# does NOT expose the hint as the EditText's `text`. Instead each hint is a SEPARATE
# TextView rendered ABOVE its EditText, and the EditText node itself has text="".
# That is why rounds 1-3 hunted for an EditText whose text contained
# "Host or SSH command" and never found one -> 'name' not visible death loop.
#
# Field identification therefore uses the hint TextView as the anchor and takes the
# FIRST EditText whose bounds start BELOW the hint's bounds (mode='below').
# Every field is proven by a sentinel round-trip: tap -> focused=true -> type
# sentinel -> read the sentinel back out of the dump -> then replace with real value.

$ErrorActionPreference = 'Stop'
$global:ADB = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$global:DEV = 'emulator-5554'
$global:WDIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_f14'

function A { & $global:ADB -s $global:DEV @args }

# --- dump ---------------------------------------------------------------
function Dump([string]$name) {
    $xml = Join-Path $global:WDIR "$name.xml"
    $png = Join-Path $global:WDIR "$name.png"
    A shell "uiautomator dump /sdcard/$name.xml" | Out-Null
    A pull "/sdcard/$name.xml" $xml | Out-Null
    A shell "screencap -p /sdcard/$name.png" | Out-Null
    A pull "/sdcard/$name.png" $png | Out-Null
    return $xml
}

# --- parse: return EditText nodes as objects ---------------------------
function EditTexts([string]$xmlPath) {
    [xml]$x = Get-Content $xmlPath -Raw
    $out = @()
    $n = $x.SelectNodes('//node')
    foreach ($node in $n) {
        if ($node.class -ne 'android.widget.EditText') { continue }
        $b = $node.bounds
        if ($b -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { continue }
        $out += [pscustomobject]@{
            Text     = $node.text
            Desc     = $node.'content-desc'
            Password = $node.password
            Focused  = $node.focused
            Clickable = $node.clickable
            X1 = [int]$Matches[1]; Y1 = [int]$Matches[2]
            X2 = [int]$Matches[3]; Y2 = [int]$Matches[4]
            Raw  = "[$($Matches[1]),$($Matches[2])][$($Matches[3]),$($Matches[4])]"
            CX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            CY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        }
    }
    return $out
}

# --- parse: all labelled text nodes ------------------------------------
function Texts([string]$xmlPath) {
    [xml]$x = Get-Content $xmlPath -Raw
    $out = @()
    foreach ($node in $x.SelectNodes('//node')) {
        $t = if ($node.text) { $node.text } else { $node.'content-desc' }
        if (-not $t) { continue }
        $b = $node.bounds
        if ($b -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { continue }
        $out += [pscustomobject]@{
            Text = $t
            X1 = [int]$Matches[1]; Y1 = [int]$Matches[2]
            X2 = [int]$Matches[3]; Y2 = [int]$Matches[4]
            CX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            CY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        }
    }
    return $out
}

function ShowEdits([string]$xmlPath, [string]$tag) {
    Write-Host "--- EditText table $tag ---"
    $i = 0
    foreach ($e in (EditTexts $xmlPath)) {
        Write-Host ("  [{0}] bounds={1} cx={2} cy={3} focused={4} pw={5} text='{6}'" -f $i, $e.Raw, $e.CX, $e.CY, $e.Focused, $e.Password, $e.Text)
        $i++
    }
}

# --- the field finder: first EditText BELOW a label --------------------
function FieldBelow([string]$xmlPath, [string]$label, [int]$minGap = 0) {
    $labs = Texts $xmlPath | Where-Object { $_.Text -eq $label }
    if (-not $labs) { return $null }
    $lab = $labs[0]
    $cands = EditTexts $xmlPath | Where-Object { $_.Y1 -ge ($lab.Y2 + $minGap) } | Sort-Object Y1
    if (-not $cands) { return $null }
    return $cands[0]
}

# --- typing -------------------------------------------------------------
# `input text` cannot carry a literal space; emit KEYCODE_SPACE(62) between words.
# NEVER sends BACK(4).
function TypeText([string]$t) {
    $words = $t -split ' '
    for ($i = 0; $i -lt $words.Count; $i++) {
        if ($words[$i] -ne '') { A shell "input text `"$($words[$i])`"" | Out-Null }
        if ($i -lt $words.Count - 1) { A shell 'input keyevent 62' | Out-Null }
        Start-Sleep -Milliseconds 220
    }
}

function TypeOne([string]$t) { A shell "input text `"$t`"" | Out-Null; Start-Sleep -Milliseconds 300 }

# --- delete n chars with DEL(67), never BACK(4) -----------------------
function DelN([int]$n) {
    for ($i = 0; $i -lt $n; $i++) { A shell 'input keyevent 67' | Out-Null; Start-Sleep -Milliseconds 90 }
}

function TapXY([int]$x, [int]$y) { A shell "input tap $x $y" | Out-Null; Start-Sleep -Milliseconds 900 }

# --- keyboard collapse: TAB(61). ESC(111) disabled in dialogs, never BACK(4).
function CollapseIme { A shell 'input keyevent 61' | Out-Null; Start-Sleep -Milliseconds 700 }

# --- fast swipe inside the form body, never starting on an EditText ----
function SwipeForm([int]$x1, [int]$y1, [int]$x2, [int]$y2, [int]$ms = 80) {
    A shell "input swipe $x1 $y1 $x2 $y2 $ms" | Out-Null
    Start-Sleep -Milliseconds 500
}

function ResetFaillock {
    A shell 'run-as com.xiufeigo.opencodeshell ls -la files/ssh' | Out-Null
    A shell 'run-as com.xiufeigo.opencodeshell rm -f files/ssh/known_hosts' | Out-Null
}

function SshdLog([string]$since = '-25min') {
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '$since' --no-pager | grep -Ei 'Accepted publickey|Accepted keyboard-interactive|authentication failure|Accepted password'" 2>$null
}
