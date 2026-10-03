# v98lib.ps1 - v98 SSH-domain real-device re-verification helpers.
# Device: emulator-5554 / Pixel_9a 1080x2424@420. Discipline per task sheet:
#   never BACK(4); never ESC(111) inside dialogs; TAB(61) collapses IME;
#   screencap -p + adb pull only (no exec-out); coordinates always re-parsed
#   from a fresh dump; every field proven with a sentinel round-trip.
$ErrorActionPreference = 'Stop'
$global:ADB = 'C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$global:DEV = 'emulator-5554'
$global:WDIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98ssh'
$global:PKG = 'com.xiufeigo.opencodeshell'

function A { & $global:ADB -s $global:DEV @args }

function Dump([string]$name) {
    $xml = Join-Path $global:WDIR "$name.xml"
    $png = Join-Path $global:WDIR "$name.png"
    A shell "uiautomator dump /sdcard/$name.xml" | Out-Null
    A pull "/sdcard/$name.xml" $xml | Out-Null
    A shell "screencap -p /sdcard/$name.png" | Out-Null
    A pull "/sdcard/$name.png" $png | Out-Null
    return $xml
}

function DumpOnly([string]$name) {
    $xml = Join-Path $global:WDIR "$name.xml"
    A shell "uiautomator dump /sdcard/$name.xml" | Out-Null
    A pull "/sdcard/$name.xml" $xml | Out-Null
    return $xml
}

function EditTexts([string]$xmlPath) {
    [xml]$x = Get-Content $xmlPath -Raw
    $out = @()
    foreach ($node in $x.SelectNodes('//node')) {
        if ($node.class -ne 'android.widget.EditText') { continue }
        $b = $node.bounds
        if ($b -notmatch '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') { continue }
        $out += [pscustomobject]@{
            Text = $node.text; Password = $node.password; Focused = $node.focused
            X1 = [int]$Matches[1]; Y1 = [int]$Matches[2]
            X2 = [int]$Matches[3]; Y2 = [int]$Matches[4]
            Raw  = "[$($Matches[1]),$($Matches[2])][$($Matches[3]),$($Matches[4])]"
            CX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            CY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        }
    }
    return $out
}

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
        Write-Host ("  [{0}] bounds={1} c=({2},{3}) focused={4} pw={5} text='{6}'" -f $i, $e.Raw, $e.CX, $e.CY, $e.Focused, $e.Password, $e.Text)
        $i++
    }
}

function ShowTexts([string]$xmlPath, [string]$tag) {
    Write-Host "--- Texts $tag ---"
    foreach ($t in (Texts $xmlPath)) {
        Write-Host ("  '{0}'  [{1},{2}][{3},{4}] c=({5},{6})" -f $t.Text, $t.X1, $t.Y1, $t.X2, $t.Y2, $t.CX, $t.CY)
    }
}

# first EditText BELOW a label TextView (the hint is its own TextView, EditText text="")
function FieldBelow([string]$xmlPath, [string]$label, [int]$minGap = 0) {
    $labs = Texts $xmlPath | Where-Object { $_.Text -eq $label }
    if (-not $labs) { return $null }
    $lab = $labs[0]
    $cands = EditTexts $xmlPath | Where-Object { $_.Y1 -ge ($lab.Y2 + $minGap) } | Sort-Object Y1
    if (-not $cands) { return $null }
    return $cands[0]
}

# find a text node whose text CONTAINS $s
function TextLike([string]$xmlPath, [string]$s) {
    return (Texts $xmlPath | Where-Object { $_.Text -like "*$s*" } | Select-Object -First 1)
}
function TextExact([string]$xmlPath, [string]$s) {
    return (Texts $xmlPath | Where-Object { $_.Text -eq $s } | Select-Object -First 1)
}

function TypeText([string]$t) {
    $words = $t -split ' '
    for ($i = 0; $i -lt $words.Count; $i++) {
        if ($words[$i] -ne '') { A shell "input text `"$($words[$i])`"" | Out-Null }
        if ($i -lt $words.Count - 1) { A shell 'input keyevent 62' | Out-Null }
        Start-Sleep -Milliseconds 200
    }
}
function TypeOne([string]$t) { A shell "input text `"$t`"" | Out-Null; Start-Sleep -Milliseconds 300 }
function DelN([int]$n) { for ($i = 0; $i -lt $n; $i++) { A shell 'input keyevent 67' | Out-Null; Start-Sleep -Milliseconds 80 } }
function TapXY([int]$x, [int]$y) { A shell "input tap $x $y" | Out-Null; Start-Sleep -Milliseconds 850 }
function CollapseIme { A shell 'input keyevent 61' | Out-Null; Start-Sleep -Milliseconds 700 }
function SwipeForm([int]$x1, [int]$y1, [int]$x2, [int]$y2, [int]$ms = 80) {
    A shell "input swipe $x1 $y1 $x2 $y2 $ms" | Out-Null; Start-Sleep -Milliseconds 450
}

function ResetFaillock {
    A shell "run-as $global:PKG rm -f files/ssh/known_hosts" | Out-Null
}

# SSH-card probes ----------------------------------------------------------------
# D1 fingerprint: the degraded card is `Password: ` (keyboard-interactive).
function ProbeCards([string]$xmlPath) {
    $ts = (Texts $xmlPath).Text
    $hostCard = [bool]($ts | Where-Object { $_ -eq 'Verify host identity' })
    $authCard = [bool]($ts | Where-Object { $_ -eq 'SSH authentication' })
    $pwCard = [bool]($ts | Where-Object { $_ -like 'Password: *' -or $_ -eq 'Password:' })
    # passphrase card subtitle echoes the key path
    $passCard = [bool]($ts | Where-Object { $_ -like 'Passphrase for *' })
    $hostKeyErr = [bool]($ts | Where-Object { $_ -like 'The host key*' -or $_ -like '*host key*changed*' })
    return [pscustomobject]@{
        HostCard = $hostCard; AuthCard = $authCard; PwCard = $pwCard
        PassCard = $passCard; HostKeyErr = $hostKeyErr
        HasCard = ($hostCard -or $authCard -or $pwCard)
    }
}

# Row-state probe for a server entry.
function ProbeRows([string]$xmlPath) {
    $ts = (Texts $xmlPath).Text
    [pscustomobject]@{
        Connected      = [bool]($ts | Where-Object { $_ -eq 'Connected' })
        AuthRequired   = [bool]($ts | Where-Object { $_ -eq 'Authentication required' })
        NotReachable   = [bool]($ts | Where-Object { $_ -eq 'Server not reachable' })
        CouldNotEst    = [bool]($ts | Where-Object { $_ -like 'Could not establish the SSH connection*' })
        ConnFailed     = [bool]($ts | Where-Object { $_ -eq 'Connection failed' })
        HasReconnect   = [bool]($ts | Where-Object { $_ -eq 'Reconnect' })
        HasDisconnect  = [bool]($ts | Where-Object { $_ -eq 'Disconnect' })
    }
}

function EntryPresent([string]$xmlPath, [string]$name) {
    return [bool]((Texts $xmlPath).Text | Where-Object { $_ -eq $name })
}

function SshdLog([string]$since = '-25min') {
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '$since' --no-pager | grep -Ei 'Accepted publickey|Accepted keyboard-interactive|Accepted password|authentication failure|Failed password'" 2>$null
}
function SshdAll([string]$since = '-25min') {
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '$since' --no-pager" 2>$null
}
function HostStamp { (Get-Date).ToString('HH:mm:ss') }
