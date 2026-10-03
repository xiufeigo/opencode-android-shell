# E2ECHK5558_run.ps1 - helper library, RECALIBRATED for emulator-5558 = Pixel_10_Pro_XL.
#
# Derived from research/v97_verify/5554/E2ECHK5554_run.ps1 (which targeted Pixel_9a,
# 1080 wide). This device is 1344x2992 @480dpi, so EVERY 1080-width constant in the
# 5554 library is wrong here:
#   - AssertDevice expected 'Pixel_9a'      -> now 'Pixel_10_Pro_XL'
#   - Fields() filtered EditText by x1==138 -> now: containment inside the form body
#     ScrollView + height>=50 (device-agnostic, no x offset)
#   - FormScrollRect anchor test x1==138     -> now: any EditText h>=50 inside a ScrollView
#   - swipe duration 400ms/250ms/350ms      -> now 80ms (only FAST swipes displace)
#   - GotoSettings/GotoServers hardcoded x  -> now derived from the dump at runtime
#
# Discipline preserved:
#   - NO keyevent 4 (BACK) anywhere; IME collapse uses keyevent 61 (TAB).
#   - NO keyevent 111 (ESC) inside Settings dialogs.
#   - Screenshots: screencap -p /sdcard/... + adb pull. NEVER exec-out.
#   - ASCII ONLY.
param(
    [string]$Dev = 'emulator-5558'
)
$ErrorActionPreference = 'Stop'
$global:ADB  = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$global:DIR  = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5558_f14'
$global:DEV  = $Dev
$global:PKG  = 'com.xiufeigo.opencodeshell'
$global:KEY_REPO = 'C:\sensorsdata\main\program\opencode-android-shell\research\v95_verify\id_ed25519'
$global:SWIPEMS = 80
Write-Host "E2ECHK5558 bound to device: $Dev  evidence dir: $global:DIR"

# ---------- basic adb plumbing ----------
function A { & $global:ADB -s $global:DEV @args }

function Shot($name) {
    $dst = Join-Path $global:DIR "$name.png"
    A shell "screencap -p /sdcard/_e2echk5558.png" | Out-Null
    A pull /sdcard/_e2echk5558.png $dst | Out-Null
    A shell "rm -f /sdcard/_e2echk5558.png" | Out-Null
    $len = (Get-Item $dst).Length
    if ($len -lt 20000) { Write-Host "WARN $name.png only $len bytes - inspect before trusting" }
    Write-Host "SHOT $name.png bytes=$len"
}

function Ui($name) {
    $ErrorActionPreference = 'Continue'
    A shell "uiautomator dump /sdcard/_e2echk5558.xml" 2>&1 | Out-Null
    A pull "/sdcard/_e2echk5558.xml" (Join-Path $global:DIR "ui_$name.xml") 2>&1 | Out-Null
    A shell "rm -f /sdcard/_e2echk5558.xml" 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
    Write-Host "UI ui_$name.xml"
}

function Nodes($name) {
    Ui $name
    python (Join-Path $global:DIR 'E2ECHK5558_nodes.py') (Join-Path $global:DIR "ui_$name.xml")
}

function Has($nodes, [string]$pat) { [bool]($nodes | Select-String -Pattern $pat -Quiet) }

function CenterOf($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
        $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}
function RectOf($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        return ,@([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    }
    return $null
}
function FieldH($line) { $r = RectOf $line; if (-not $r) { return 0 }; return ($r[3] - $r[1]) }
function InRect($r, $c) { return ($c[0] -ge $r[0] -and $c[1] -ge $r[1] -and $c[2] -le $r[2] -and $c[3] -le $r[3]) }

function Tap($x, $y, $ms = 700) {
    A shell "input tap $x $y" | Out-Null
    Start-Sleep -Milliseconds $ms
}

function TapMatch([string]$pat, [string]$dump = 'E2ECHK5558_tap_tmp', [int]$index = 0) {
    $n = Nodes $dump
    $m = @($n | Select-String -Pattern $pat)
    if ($m.Count -le $index) {
        Write-Host "NOMATCH '$pat' ($($m.Count) hit(s)); sample nodes:"
        ($n | Select-Object -First 20) | ForEach-Object { Write-Host "   $_" }
        throw "TapMatch: no node matches '$pat'"
    }
    $p = CenterOf $m[$index].Line
    if (-not $p) { throw "TapMatch: node has no bounds" }
    Write-Host "TAP '$pat' @ $($p[0]),$($p[1])"
    Tap $p[0] $p[1]
    return $m[$index].Line
}

# ASCII text: <=8 char segments, 1.5s between, real KEYCODE_SPACE(62) between words,
# never after the last word. %s is NOT used.
function TypeText($t, $chunk = 8, $sleepMs = 1200) {
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
            Start-Sleep -Milliseconds 350
        }
    }
}

# ---------- WSL / sshd ----------
function ResetFaillock {
    wsl -d Arch -e bash -lc "sudo faillock --user sshtest --reset 2>/dev/null; echo faillock-reset"
}
function SshdAcceptedPub([string]$Since = '-20min') {
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '$Since' --no-pager | grep -Ei 'Accepted publickey|Accepted keyboard-interactive|authentication failure' | tail -40"
}
function MarkNow { return (Get-Date).ToUniversalTime().AddHours(8).ToString('yyyy-MM-dd HH:mm:ss') }

# ---------- device state ----------
function AssertDevice {
    $name = (A emu avd name) 2>$null | Select-Object -First 1
    if ("$name".Trim() -ne 'Pixel_10_Pro_XL') { throw "device $global:DEV is '$name', expected Pixel_10_Pro_XL - STOP" }
    $sz = (A shell "wm size" | Out-String)
    $dn = (A shell "wm density" | Out-String)
    Write-Host "DEVICE OK: $global:DEV = Pixel_10_Pro_XL"
    Write-Host "SIZE $dn $sz"
    if ($sz -notmatch '1344x2992') { throw "unexpected screen size: $sz" }
}

function AppVersion {
    (A shell "dumpsys package $global:PKG" | Out-String) -split "`n" |
        Select-String -Pattern 'versionName|lastUpdateTime|firstInstallTime' | ForEach-Object { $_.ToString().Trim() }
}

function LaunchApp {
    A shell "am force-stop $global:PKG" | Out-Null
    Start-Sleep -Milliseconds 800
    A shell "am start -n $global:PKG/.OcActivity" | Out-Null
    Start-Sleep -Seconds 4
    (A shell "dumpsys window | grep mCurrentFocus" | Out-String).Trim()
}

# Provision the ed25519 key into the app private dir (idempotent, md5-verified).
function EnsureKey {
    $bytes = [IO.File]::ReadAllBytes($global:KEY_REPO)
    if ($bytes.Length -ne 444) { throw "repo key is $($bytes.Length) bytes, expected 444" }
    $repoMd5 = (Get-FileHash $global:KEY_REPO -Algorithm MD5).Hash.ToLower()
    $b64 = [Convert]::ToBase64String($bytes)
    A shell "run-as $global:PKG sh -c 'echo $b64 | base64 -d > files/id_ed25519'" | Out-Null
    $devMd5 = ((A shell "run-as $global:PKG md5sum files/id_ed25519" | Out-String) -split '\s+')[0].Trim()
    $ls = (A shell "run-as $global:PKG ls -l files" | Out-String).Trim()
    Write-Host "KEY repo=$repoMd5 device=$devMd5"
    Write-Host $ls
    if ($devMd5 -ne $repoMd5) { throw "key md5 mismatch after provisioning" }
    Write-Host "KEY OK: files/id_ed25519 readable by app uid"
}

# ---------- navigation (dump-derived, no 1080-width constants) ----------
function Grab { Nodes 'E2ECHK5558_nav_tmp' }

function SettingsOpen($nodes) {
    [bool]($nodes | Select-String -Pattern '^(General|Appearance|Notifications|Servers|Shell|Experimental|About)\t' -Quiet)
}

# Exact tab-name match on the FIRST tab-delimited field (no regex char-class traps).
function TabNodes($n) {
    $names = @('General', 'Appearance', 'Notifications', 'Pairing', 'Projects', 'Worktrees',
        'Providers', 'Models', 'Servers', 'Shell', 'Experimental', 'About')
    $out = @()
    foreach ($line in $n) {
        $f = ($line -split "`t")[0]
        if ($names -contains $f) { $out += ,@($f, $line) }
    }
    return $out
}
# The tab strip is the row of tab nodes sharing the most common y band. Section headers
# (e.g. "Notifications" as a page title) sit elsewhere, so we cluster on y.
function TabRow($n) {
    $hits = @()
    foreach ($t in (TabNodes $n)) {
        $r = RectOf $t[1]
        if ($r) { $hits += ,@([int](($r[1] + $r[3]) / 2), $r[0], $r[2], $t[0]) }
    }
    if ($hits.Count -eq 0) { return $null }
    $groups = @{}
    foreach ($h in $hits) {
        $k = [int]([Math]::Round($h[0] / 60))
        if (-not $groups.ContainsKey($k)) { $groups[$k] = @() }
        $groups[$k] += , $h
    }
    $best = $null; $bestN = 0
    foreach ($k in $groups.Keys) {
        if ($groups[$k].Count -gt $bestN) { $bestN = $groups[$k].Count; $best = $groups[$k] }
    }
    if ($bestN -lt 2) { return $null }
    $ys = @($best | ForEach-Object { $_[0] } | Sort-Object)
    $med = $ys[[int]($ys.Count / 2)]
    $xmin = ($best | ForEach-Object { $_[1] } | Measure-Object -Minimum).Minimum
    $xmax = ($best | ForEach-Object { $_[2] } | Measure-Object -Maximum).Maximum
    return ,@($med, $xmin, $xmax, ($best | ForEach-Object { $_[3] }))
}

# Home -> Settings dialog. The tab strip swipe is derived from the actual bounds of a
# known tab (General/Appearance/Notifications) in the current dump.
# Home -> Settings dialog. The app RESTORES its last state on relaunch, so we may
# already be inside Settings (or on a session tab). Normalise to a known state first.
function GoHomeIfNeeded {
    $n = Grab
    if ($n | Select-String -Pattern '^Settings\t' -Quiet) { return $n }
    $h = $n | Select-String -Pattern '^Home\t' | Select-Object -First 1
    if ($h) {
        $p = CenterOf $h.Line
        Write-Host "  tap Home @ $($p[0]),$($p[1]) (normalise relaunch state)"
        Tap $p[0] $p[1] 1200
        $n = Grab
    }
    return $n
}

function GotoSettings {
    $n = GoHomeIfNeeded
    if (SettingsOpen $n) { Write-Host 'Settings already open'; return }
    $s = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
    if (-not $s) { Shot 'E2ECHK5558_NAV_abort'; throw 'no Settings affordance on home screen' }
    $p = CenterOf $s.Line
    Tap $p[0] $p[1]
    Start-Sleep -Seconds 1
    $n = Grab
    if (-not (SettingsOpen $n)) { Shot 'E2ECHK5558_NAV_abort'; throw 'Settings dialog did not open' }
    # scroll the tab strip to its far end: derive the band from the actual tab row
    for ($i = 0; $i -lt 8; $i++) {
        $row = TabRow $n
        if (-not $row) { break }
        if ($n | Select-String -Pattern '^Servers\t' -Quiet) { break }
        $ty = $row[0]
        $x1 = $row[1] + 10
        $x2 = $row[2] - 10
        A shell "input swipe $x2 $ty $x1 $ty $($global:SWIPEMS)" | Out-Null
        Start-Sleep -Milliseconds 500
        $n = Grab
    }
}

# Settings -> Servers tab -> Servers panel.
function GotoServers {
    GotoSettings
    $srv = $null
    for ($i = 0; $i -lt 8; $i++) {
        $n = Grab
        $srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1
        if ($srv) { break }
        $row = TabRow $n
        if ($row) {
            $ty = $row[0]
            A shell "input swipe $($row[2] - 10) $ty $($row[1] + 10) $ty $($global:SWIPEMS)" | Out-Null
        }
        Start-Sleep -Milliseconds 700
    }
    if (-not $srv) { Shot 'E2ECHK5558_NAV_abort'; throw 'Servers tab not found' }
    $p = CenterOf $srv.Line
    Tap $p[0] $p[1]
    Start-Sleep -Seconds 1
}

# Servers panel -> Add SSH server form (idempotent, verified).
# Deterministic path to the Add-SSH form from ANY current state.
# Handles: form already open / servers panel showing / settings dialog / home.
function ReachForm {
    $n = Grab
    if ((Has $n 'Host or SSH command') -or (Has $n '^Authentication\t')) { Write-Host '  form already open'; return }
    if ($n | Select-String -Pattern '^Add SSH server\t' -Quiet) { Write-Host '  servers panel already showing'; return }
    # in a Settings dialog? (any tab row node present)
    $row = TabRow $n
    if ($row) {
        for ($i = 0; $i -lt 8; $i++) {
            $n = Grab
            if ($n | Select-String -Pattern '^Servers\t' -Quiet) { break }
            $row = TabRow $n
            if (-not $row) { break }
            A shell "input swipe $($row[2] - 10) $($row[0]) $($row[1] + 10) $($row[0]) $($global:SWIPEMS)" | Out-Null
            Start-Sleep -Milliseconds 600
        }
        $srv = Grab | Select-String -Pattern '^Servers\t' | Select-Object -First 1
        if ($srv) {
            $p = CenterOf $srv.Line
            Write-Host "  tap Servers tab @ $($p[0]),$($p[1])"
            Tap $p[0] $p[1] 1000
        }
    } else {
        # home screen: tap the Home BUTTON (top-left nav), then Settings
        $hb = $n | Select-String -Pattern 'Home\t.*android.widget.Button' | Select-Object -First 1
        if ($hb) { $p = CenterOf $hb.Line; Write-Host "  tap Home btn @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] 1200; $n = Grab }
        $s = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
        if (-not $s) { Shot 'E2ECHK5558_NAV_abort'; throw 'no Home/Settings affordance' }
        $p = CenterOf $s.Line
        Write-Host "  tap Settings @ $($p[0]),$($p[1])"
        Tap $p[0] $p[1] 1200
        GotoServers
    }
    OpenAddForm
}

# Servers panel -> Add SSH server form (idempotent, verified).
function OpenAddForm {
    $n = Grab
    if ((Has $n 'Host or SSH command') -or (Has $n '^Authentication\t')) { Write-Host 'form already open'; return }
    for ($i = 0; $i -lt 3; $i++) {
        $n = Grab
        $add = $n | Select-String -Pattern '^Add SSH server\t' | Select-Object -First 1
        if (-not $add) { Shot 'E2ECHK5558_NAV_abort'; throw 'Add SSH server button not found' }
        $p = CenterOf $add.Line
        Tap $p[0] $p[1]
        Start-Sleep -Seconds 1
        $n = Grab
        if ((Has $n 'Host or SSH command') -or (Has $n '^Authentication\t')) { Write-Host "form open (attempt $($i + 1))"; return }
        Write-Host "  form did not open (attempt $($i + 1))"
    }
    Shot 'E2ECHK5558_NAV_abort'
    throw 'Add SSH server form did not open after 3 attempts'
}

# ---------- SSH form geometry (RECALIBRATED) ----------
# Smallest ScrollView that CONTAINS a form anchor. Anchors are the form's own labels
# plus any EditText with height >= 50 (no x-offset assumption: the 5554 x1==138 test
# is invalid on a 1344-wide screen).
function FormScrollRect {
    $xml = Join-Path $global:DIR 'ui_E2ECHK5558_nav_tmp.xml'
    if (-not (Test-Path $xml)) { return $null }
    $x = $null
    try { $x = [xml](Get-Content $xml -Raw) } catch { return $null }
    $scrolls = New-Object System.Collections.ArrayList
    $anchors = New-Object System.Collections.ArrayList
    $stack = New-Object System.Collections.Stack
    $stack.Push($x.hierarchy)
    while ($stack.Count -gt 0) {
        $nd = $stack.Pop()
        if ($null -eq $nd) { continue }
        if ("$($nd.bounds)" -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $r = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
            if ($nd.class -eq 'android.widget.ScrollView') { [void]$scrolls.Add($r) }
            $t = "$($nd.text)"
            $isAnchor = ($t -eq 'Host or SSH command') -or ($t -eq 'Server name (optional)') -or
                        ($t -eq 'Authentication') -or ($t -eq 'Private key') -or ($t -eq 'Passphrase')
            $isTallEt = ($nd.class -eq 'android.widget.EditText') -and (($r[3] - $r[1]) -ge 50)
            if ($isAnchor -or $isTallEt) { [void]$anchors.Add($r) }
        }
        foreach ($c in $nd.node) { $stack.Push($c) }
    }
    if ($anchors.Count -eq 0) { return $null }
    $best = $null; $bestArea = [int]::MaxValue
    foreach ($s in $scrolls) {
        foreach ($a in $anchors) {
            if (InRect $s $a) {
                $area = ($s[2] - $s[0]) * ($s[3] - $s[1])
                if ($area -gt 0 -and $area -lt $bestArea) { $best = $s; $bestArea = $area }
                break
            }
        }
    }
    return $best
}

# Scroll the form body. x is derived from the form rect (never a fixed 1000), and the
# gesture is FAST (80ms) because slow swipes produce zero displacement on this build.
function FormSwipe($n, [int]$dir = -1) {
    $r = FormScrollRect
    if (-not $r) {
        Write-Host '  form swipe SKIPPED (form body ScrollView not found - swiping would drag-dismiss)'
        return
    }
    $x = $r[2] - 20
    $top = $r[1] + 30
    $bot = $r[3] - 30
    if (($bot - $top) -lt 100) { Write-Host "  form swipe SKIPPED (band too small: $top..$bot)"; return }
    if ($dir -ge 0) { $y1 = $top; $y2 = $bot } else { $y1 = $bot; $y2 = $top }
    Write-Host "  form swipe x=$x $y1 -> $y2 (rect $($r[0]),$($r[1])..$($r[2]),$($r[3]) ms=$($global:SWIPEMS))"
    A shell "input swipe $x $y1 $x $y2 $($global:SWIPEMS)" | Out-Null
    Start-Sleep -Milliseconds 700
}

# Form fields: EditTexts that belong to the Add-SSH card.
# RECALIBRATION NOTE (5558 / 1344 wide): the last field ("Server name (optional)")
# is CLIPPED by the card viewport - dump shows [156,2136][935,2154], height 18 and
# y1 BELOW the form ScrollView bottom (2082). The 5554 rules (h>=50 + full
# containment in the ScrollView) therefore reject it, which is what deadlocked the
# previous testers: 'name'/'host' never resolve and ScrollToNear spins.
# Rule here: an EditText is a form field when it starts below the form rect top and
# lies within the form rect's x-span. The Settings search box sits ABOVE the form
# rect top (y=749 vs 1643) so it is excluded without any x-offset constant.
function Fields($n) {
    $r = FormScrollRect
    $out = New-Object System.Collections.ArrayList
    foreach ($line in @($n | Select-String -Pattern 'EditText\t' | ForEach-Object { $_.Line })) {
        $fr = RectOf $line
        if (-not $fr) { continue }
        if ($r) {
            if ($fr[1] -lt $r[1]) { continue }                    # above the card
            if ($fr[0] -lt $r[0] - 40) { continue }               # left of the card
            if ($fr[2] -gt $r[2] + 40) { continue }               # right of the card
        }
        [void]$out.Add(@($fr[1], $line))
    }
    return @($out | Sort-Object { $_[0] } | ForEach-Object { $_[1] })
}

function FieldNear($n, $label, $mode = 'below') {
    $lab = $n | Select-String -Pattern ([regex]::Escape($label)) | Select-Object -First 1
    if (-not $lab) { return $null }
    $lr = RectOf $lab.Line
    if (-not $lr) { return $null }
    $ly = $lr[1]
    $cands = New-Object System.Collections.ArrayList
    foreach ($f in (Fields $n)) {
        $fr = RectOf $f
        if (-not $fr) { continue }
        if ((FieldH $f) -lt 50) { continue }
        if ($mode -eq 'below') {
            if ($fr[1] -gt $ly -and $fr[1] -lt ($ly + 300)) { [void]$cands.Add(@(($fr[1] - $ly), $f)) }
        } else {
            if ($fr[3] -le $ly -and $fr[3] -gt ($ly - 150)) { [void]$cands.Add(@(($ly - $fr[3]), $f)) }
        }
    }
    if ($cands.Count -eq 0) { return $null }
    $best = ($cands | Sort-Object { $_[0] })[0]
    return $best[1]
}

function CollapseIme {
    Write-Host '  collapse IME (keyevent 61 TAB)'
    A shell "input keyevent 61" | Out-Null
    Start-Sleep -Milliseconds 900
}

function ScrollToNear($label, $what, $mode = 'below') {
    for ($i = 0; $i -lt 12; $i++) {
        $n = Grab
        $f = FieldNear $n $label $mode
        if ($f) { return ,@($f, $n) }
        $d = -1
        if ($i -ge 6) { $d = 1 }
        Write-Host "  '$what' not visible (attempt $($i + 1), dir $d)"
        if ($i -eq 2 -or $i -eq 7) { CollapseIme; $i = $i - 1; continue }
        FormSwipe $n $d
    }
    return $null
}

function ScrollToPw($what) {
    for ($i = 0; $i -lt 6; $i++) {
        $n = Grab
        $hit = Fields $n | Where-Object { $_ -match 'pw=true' -and (FieldH $_) -ge 50 } | Select-Object -First 1
        if ($hit) { return ,@($hit, $n) }
        if ($i -eq 1) { CollapseIme; $i = $i - 1; continue }
        FormSwipe $n
    }
    return $null
}

function TapField($line, $what, $anchor = '', $mode = 'below') {
    for ($i = 0; $i -lt 3; $i++) {
        $p = CenterOf $line
        if (-not $p) { return $false }
        Write-Host "  tap $what @ $($p[0]),$($p[1])"
        Tap $p[0] $p[1]
        Start-Sleep -Milliseconds 900
        $n = Grab
        $foc = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'foc=true' } | Select-Object -First 1
        if ($foc) { Write-Host "    focused: $((($foc.Line -split "`t")[0]))"; return $true }
        Write-Host "  $what not focused (attempt $($i + 1)); retrying"
        if ($anchor -ne '') {
            $line = FieldNear $n $anchor $mode
            if (-not $line) { FormSwipe $n -1; $n = Grab; $line = FieldNear $n $anchor $mode }
        } else {
            FormSwipe $n -1
            $n = Grab
            $line = (Fields $n | Select-Object -First 1)
        }
        if (-not $line) { return $false }
    }
    return $false
}

function FieldsAboveAuth($n) {
    $auth = $n | Select-String -Pattern '^Authentication\t' | Select-Object -First 1
    if (-not $auth) { return @() }
    $ar = RectOf $auth.Line
    if (-not $ar) { return @() }
    $ay = $ar[1]
    $out = @()
    foreach ($f in (Fields $n)) {
        if ($f -match 'pw=true') { continue }
        $fr = RectOf $f
        if (-not $fr) { continue }
        if (($fr[3] -le $ay) -and ((FieldH $f) -ge 50)) { $out += ,@($fr[1], $f) }
    }
    return @($out | Sort-Object { $_[0] })
}

# Reset the form body scroll to the TOP (host field lives at the top). dir=+1 swipes
# finger-down, which reveals EARLIER content. Without this, a form left scrolled down
# hides the Host/Name labels and ScrollToNear spins forever.
function ScrollFormToTop {
    for ($i = 0; $i -lt 6; $i++) {
        $n = Grab
        if ($n | Select-String -Pattern 'Host or SSH command' -Quiet) { Write-Host "  form at top after $($i + 1) pass(es)"; return $true }
        FormSwipe $n 1
    }
    $n = Grab
    if ($n | Select-String -Pattern 'Host or SSH command' -Quiet) { return $true }
    return $false
}

# Fill the ALREADY-OPEN Add SSH server form.
# RECALIBRATION (5558): field order is Host -> Server name -> Authentication, and
# 'Authentication' is NOT visible in the initial dump. The 5554 anchors (name =
# field ABOVE Authentication) cannot resolve on this device. Here each field is
# anchored DIRECTLY to its own label ('below' within 300px), which is device-agnostic.
function Fill-SshForm {
    param(
        [Parameter(Mandatory = $true)][string]$Target,
        [Parameter(Mandatory = $true)][string]$Name,
        [ValidateSet('Password', 'PrivateKey')][string]$Auth = 'Password',
        [string]$Password = '',
        [string]$KeyPath = '',
        [string]$Passphrase = '',
        [string]$Tag = 'F'
    )
    $n = Grab
    if (-not ((Has $n 'Host or SSH command') -or (Has $n '^Authentication\t'))) { throw 'Add SSH server form is not on screen' }

    # bring the form body to the top so the Host/Name fields are on screen
    Write-Host '  scroll form to top'
    ScrollFormToTop | Out-Null

    # --- host: 1st form EditText at the top (direct index, no label search).
    # Label-anchored search fails here because when the form scrolls to the auth area
    # the Host label scrolls OFF-SCREEN and disappears from the dump entirely. ---
    $n = Grab
    $fl = @(Fields $n)
    Write-Host "  form fields at top: $($fl.Count)"
    $fl | ForEach-Object { Write-Host "    fld [$_]" }
    if ($fl.Count -lt 2) { throw "expected >=2 form fields (host,name), got $($fl.Count)" }
    if (-not (TapField $fl[0] 'host')) { throw 'host never focused' }
    TypeText $Target
    Start-Sleep -Milliseconds 700
    CollapseIme

    # --- name: 2nd form EditText ---
    $n = Grab
    $fl = @(Fields $n)
    if ($fl.Count -lt 2) { throw "name field not visible after host ($($fl.Count) fields)" }
    if (-not (TapField $fl[1] 'name')) { throw 'name never focused' }
    TypeText $Name
    Start-Sleep -Milliseconds 700
    CollapseIme

    if ($Auth -eq 'PrivateKey') {
        # Authentication row may be below the fold; scroll to it.
        $tog = $null
        for ($i = 0; $i -lt 8; $i++) {
            $n = Grab
            $tog = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1
            if ($tog) { break }
            Write-Host "  'Password' toggle not visible (attempt $($i + 1))"
            FormSwipe $n -1
        }
        if ($tog) {
            $p = CenterOf $tog.Line; Write-Host "  tap Password toggle @ $($p[0]),$($p[1])"; Tap $p[0] $p[1]; Start-Sleep -Seconds 1
            $n = Grab
            $opt = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
            if ($opt) { $p = CenterOf $opt.Line; Write-Host "  tap Private key @ $($p[0]),$($p[1])"; Tap $p[0] $p[1]; Start-Sleep -Seconds 1 }
            else { throw 'Private key option not offered' }
        }
        $res = ScrollToNear 'Private key' 'keypath' 'below'
        if (-not $res) { throw 'key path field unreachable' }
        if (-not (TapField $res[0] 'keypath' 'Private key' 'below')) { throw 'keypath never focused' }
        if ($KeyPath -ne '') { TypeText $KeyPath }
        Start-Sleep -Milliseconds 700
        if ($Passphrase -ne '') {
            $res = ScrollToPw 'passphrase'
            if (-not $res) { throw 'passphrase field unreachable' }
            if (-not (TapField $res[0] 'passphrase')) { throw 'passphrase never focused' }
            TypeText $Passphrase
        } else {
            Write-Host '  passphrase intentionally left EMPTY (F12a corrected criteria)'
        }
    } else {
        $res = ScrollToPw 'password'
        if (-not $res) { throw 'password field unreachable' }
        if (-not (TapField $res[0] 'password')) { throw 'password never focused' }
        if ($Password -ne '') { TypeText $Password }
    }
    Shot "E2ECHK5558_${Tag}_20_form_typed"
    $n = Grab
    Write-Host '--- form state ---'
    Fields $n | ForEach-Object { Write-Host "  FLD $_" }
    ($n | Select-String -Pattern '^Add server\t|^Cancel\t|^Password\t|^Private key\t|^Passphrase\t') |
        ForEach-Object { Write-Host "  $_" }
    Shot "E2ECHK5558_${Tag}_21_form_ready"
}

$script:LastSubmit = $null
function SubmitForm {
    $n = Grab
    $sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
    if (-not $sub) { throw 'Add server button not visible' }
    $p = CenterOf $sub.Line
    $script:LastSubmit = $p
    Write-Host "SUBMIT @ $($p[0]),$($p[1])"
    Tap $p[0] $p[1]
}

# ---------- auth card ----------
# FieldX/Y = credential field centre with IME UP; ContX/Y = Continue centre with IME UP.
# Learn them once with Rehearse-Card.
function Answer-Card {
    param(
        [Parameter(Mandatory = $true)][int]$ReconnectX,
        [Parameter(Mandatory = $true)][int]$ReconnectY,
        [string]$Secret = 'opencode',
        [int]$FieldX = 672,
        [int]$FieldY = 1641,
        [int]$ContX = 1000,
        [int]$ContY = 1347,
        [string]$Tag = 'A',
        [int]$CardWaitMs = 2800,
        [int]$Samples = 10
    )
    ResetFaillock | Out-Null
    Write-Host "RECONNECT @ $ReconnectX,$ReconnectY"
    A shell "input tap $ReconnectX $ReconnectY" | Out-Null
    Start-Sleep -Milliseconds $CardWaitMs
    # TIGHT BLOCK: no dumps/screenshots between card-up and Continue
    A shell "input tap $FieldX $FieldY" | Out-Null
    Start-Sleep -Milliseconds 800
    $words = @($Secret -split ' ' | Where-Object { $_ -ne '' })
    for ($w = 0; $w -lt $words.Count; $w++) {
        $word = $words[$w]
        for ($i = 0; $i -lt $word.Length; $i += 8) {
            $seg = $word.Substring($i, [Math]::Min(8, $word.Length - $i))
            A shell "input text '$seg'" | Out-Null
            Start-Sleep -Milliseconds 900
        }
        if ($w -lt $words.Count - 1) { A shell "input keyevent 62" | Out-Null; Start-Sleep -Milliseconds 300 }
    }
    A shell "input tap $ContX $ContY" | Out-Null
    Start-Sleep -Milliseconds 2500
    Shot "E2ECHK5558_${Tag}_40_result"
    Write-Host '--- outcome samples ---'
    for ($s = 1; $s -le $Samples; $s++) {
        $n = Nodes "E2ECHK5558_${Tag}_41_s$s"
        $bits = @()
        foreach ($pat in @('Connected', 'Could not establish', 'Authentication required', 'Connecting', 'Disconnected', 'left running', 'SSH authentication', 'passphrase', 'Passphrase', 'left to unlock')) {
            if (Has $n $pat) { $bits += $pat }
        }
        Write-Output ("  s{0,-2} {1}" -f $s, ($bits -join ' | '))
        if ($bits -contains 'Connected') { break }
    }
    Shot "E2ECHK5558_${Tag}_42_final"
}

# Learn the keyboard-up coordinates for Answer-Card.
function Rehearse-Card {
    param([Parameter(Mandatory = $true)][int]$ReconnectX, [Parameter(Mandatory = $true)][int]$ReconnectY, [string]$Tag = 'R')
    ResetFaillock | Out-Null
    A shell "input tap $ReconnectX $ReconnectY" | Out-Null
    Start-Sleep -Milliseconds 2800
    $n = Grab
    Shot "E2ECHK5558_${Tag}_10_card_noime"
    Write-Host '--- card, IME down ---'
    ($n | Select-String -Pattern 'SSH authentication|Password:|Passphrase|Cancel|Continue|EditText') | ForEach-Object { Write-Host "  $($_.Line)" }
    $fld = $n | Select-String -Pattern 'EditText\t' | Select-Object -First 1
    $con = $n | Select-String -Pattern '^Continue\t' | Select-Object -First 1
    if (-not $fld -or -not $con) { Write-Host 'REHEARSE: card layout not recognised'; return }
    $pf = CenterOf $fld.Line
    Write-Host "  tap card field @ $($pf[0]),$($pf[1])"
    Tap $pf[0] $pf[1]
    Start-Sleep -Milliseconds 1000
    $n = Grab
    Shot "E2ECHK5558_${Tag}_11_card_imeup"
    Write-Host '--- card, IME up (LEARNED coords for Answer-Card) ---'
    ($n | Select-String -Pattern 'SSH authentication|Password:|Passphrase|Cancel|Continue|EditText') | ForEach-Object { Write-Host "  $($_.Line)" }
    $fld2 = $n | Select-String -Pattern 'EditText\t' | Select-Object -First 1
    $con2 = $n | Select-String -Pattern '^Continue\t' | Select-Object -First 1
    if ($fld2) { $p = CenterOf $fld2.Line; Write-Host "LEARNED FieldX=$($p[0]) FieldY=$($p[1])" }
    if ($con2) { $p = CenterOf $con2.Line; Write-Host "LEARNED ContX=$($p[0]) ContY=$($p[1])" }
    $can = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
    if ($can) { $p = CenterOf $can.Line; Tap $p[0] $p[1]; Write-Host 'card cancelled (Cancel, never ESC)' }
    ResetFaillock | Out-Null
}
