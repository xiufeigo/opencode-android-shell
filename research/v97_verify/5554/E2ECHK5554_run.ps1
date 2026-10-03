# E2ECHK5554_run.ps1 - shared helper library for the v97 final verification round.
# Target device: emulator-5554 = Pixel_9a (verify with AssertDevice; the serial-to-AVD
# mapping was re-shuffled when Lead restarted the fleet, so NEVER trust the serial).
#
# ASCII ONLY: PS 5.1 parses a BOM-less .ps1 as ANSI and dies on non-ASCII punctuation.
#
# Discipline baked into this library:
#   - NO keyevent 4 (BACK) anywhere. IME collapse inside dialogs uses keyevent 61 (TAB).
#   - NO keyevent 111 (ESC) while a Settings dialog/overlay is open (it kills the dialog).
#   - Screenshots: screencap -p /sdcard/... + adb pull. NEVER exec-out (adb warning
#     text pollutes the PNG bytes).
#   - Shell output that must land in a file goes through cmd /c redirection.
#   - Coordinates are resolved from uiautomator dumps at runtime; no hardcoded taps.
param(
    [string]$Dev = 'emulator-5554'
)
$ErrorActionPreference = 'Stop'
$global:ADB  = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$global:DIR  = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554'
$global:DEV  = $Dev
$global:PKG  = 'com.xiufeigo.opencodeshell'
$global:KEY_REPO = 'C:\sensorsdata\main\program\opencode-android-shell\research\v95_verify\id_ed25519'
Write-Host "E2ECHK5554 bound to device: $Dev  evidence dir: $global:DIR"

# ---------- basic adb plumbing ----------
function A { & $global:ADB -s $global:DEV @args }

# Screenshot via /sdcard + pull (discipline: no exec-out).
function Shot($name) {
    $dst = Join-Path $global:DIR "$name.png"
    A shell "screencap -p /sdcard/_e2echk5554.png" | Out-Null
    A pull /sdcard/_e2echk5554.png $dst | Out-Null
    A shell "rm -f /sdcard/_e2echk5554.png" | Out-Null
    $len = (Get-Item $dst).Length
    if ($len -lt 20000) { Write-Host "WARN $name.png only $len bytes - inspect before trusting" }
    Write-Host "SHOT $name.png bytes=$len"
}

# uiautomator dump + pull. Result: ui_<name>.xml in the evidence dir.
function Ui($name) {
    $ErrorActionPreference = 'Continue'
    A shell "uiautomator dump /sdcard/_e2echk5554.xml" 2>&1 | Out-Null
    A pull "/sdcard/_e2echk5554.xml" (Join-Path $global:DIR "ui_$name.xml") 2>&1 | Out-Null
    A shell "rm -f /sdcard/_e2echk5554.xml" 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
    Write-Host "UI ui_$name.xml"
}

# Node table (text/content-desc + bounds, one node per line) via python helper.
function Nodes($name) {
    Ui $name
    python (Join-Path $global:DIR 'E2ECHK5554_nodes.py') (Join-Path $global:DIR "ui_$name.xml")
}

function Has($nodes, [string]$pat) { [bool]($nodes | Select-String -Pattern $pat -Quiet) }

# Centre of a "[x1,y1][x2,y2]" bounds pair. ALWAYS cast to int first: string
# concatenation here once produced taps at x=66132 (v96 lesson).
function CenterOf($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
        $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}

function Tap($x, $y, $ms = 700) {
    A shell "input tap $x $y" | Out-Null
    Start-Sleep -Milliseconds $ms
}

# Dump fresh, tap the Nth node line matching $pat. Returns the matched line.
function TapMatch([string]$pat, [string]$dump = 'E2ECHK5554_tap_tmp', [int]$index = 0) {
    $n = Nodes $dump
    $m = @($n | Select-String -Pattern $pat)
    if ($m.Count -le $index) {
        Write-Host "NOMATCH '$pat' ($($m.Count) hit(s)); visible text nodes:"
        ($n | Select-String -Pattern 'EditText|TextView|Button') | Select-Object -First 14 |
            ForEach-Object { Write-Host "   $($_.Line)" }
        throw "TapMatch: no node matches '$pat'"
    }
    $p = CenterOf $m[$index].Line
    if (-not $p) { throw "TapMatch: node has no bounds: $($m[$index].Line)" }
    Write-Host "TAP '$pat' @ $($p[0]),$($p[1])  <<$(($m[$index].Line -split "`t")[0])>>"
    Tap $p[0] $p[1]
    return $m[$index].Line
}

# ASCII text into the focused field: segments of <=8 chars, 1.5 s between segments,
# real KEYCODE_SPACE(62) between words and never after the last word. %s is NOT used:
# on this image it delivered a literal "%s" (v95 lesson).
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

# ---------- WSL / sshd side ----------
function ResetFaillock {
    wsl -d Arch -e bash -lc "sudo faillock --user sshtest --reset; sudo faillock --user sshtest"
}
function SshdLog([string]$Since = '-3min', [int]$Tail = 30) {
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '$Since' --no-pager | tail -$Tail"
}
# publickey acceptance proof for F12a/F14b
function SshdAcceptedPub([string]$Since = '-20min') {
    wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '$Since' --no-pager | grep -Ei 'Accepted publickey|Accepted keyboard-interactive|authentication failure|session opened' | tail -30"
}
# Timestamp marker to scope journalctl windows per scenario.
function MarkNow { return (Get-Date).ToUniversalTime().AddHours(8).ToString('yyyy-MM-dd HH:mm:ss') }

# ---------- device-side state ----------
function AssertDevice {
    $name = (A emu avd name) 2>$null | Select-Object -First 1
    if ("$name".Trim() -ne 'Pixel_9a') { throw "device $global:DEV is '$name', expected Pixel_9a - STOP" }
    Write-Host "DEVICE OK: $global:DEV = Pixel_9a"
}

function AppVersion {
    (A shell "dumpsys package $global:PKG" | Out-String) -split "`n" |
        Select-String -Pattern 'versionName|lastUpdateTime' | ForEach-Object { $_.ToString().Trim() }
}

function AppRunning {
    ((A shell "ps -A | grep $global:PKG" | Out-String).Trim().Length -gt 0)
}

function LaunchApp {
    A shell "am force-stop $global:PKG" | Out-Null
    Start-Sleep -Milliseconds 800
    A shell "am start -n $global:PKG/.OcActivity" | Out-Null
    Start-Sleep -Seconds 4
    (A shell "dumpsys window | grep mCurrentFocus" | Out-String).Trim()
}

# Provision the ed25519 test key into the app private dir. MUST be replayed after
# every APK install (uid can change; /sdcard copies stay unreadable for the app).
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

# ---------- navigation (idempotent, adapted from v96 openform.ps1) ----------
function Grab { Nodes 'E2ECHK5554_nav_tmp' }

function SettingsOpen($nodes) {
    [bool]($nodes | Select-String -Pattern '^(General|Appearance|Notifications|Servers|Shell|Experimental|About)\t' -Quiet)
}

# Home -> Settings dialog open. Never swipes when the dialog is already open
# (a swipe inside the dialog reads as drag-to-dismiss and kills it).
function GotoSettings {
    $n = Grab
    if (SettingsOpen $n) { Write-Host 'Settings already open'; return }
    $s = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
    if (-not $s) { Write-Host 'ABORT: no Settings affordance on home screen'; Shot 'E2ECHK5554_NAV_abort'; throw 'no Settings affordance' }
    $p = CenterOf $s.Line
    Tap $p[0] $p[1]
    Start-Sleep -Seconds 1
    $n = Grab
    if (-not (SettingsOpen $n)) { Shot 'E2ECHK5554_NAV_abort'; throw 'Settings dialog did not open' }
    # scroll the tab strip to its far end once (safe: dialog was just opened)
    $g = $n | Select-String -Pattern '^(General|Appearance|Notifications)\t' | Select-Object -First 1
    $ty = 744
    if ($g -and $g.Line -match '\[(\d+),(\d+)\]') { $ty = [int]$Matches[2] }
    for ($i = 0; $i -lt 6; $i++) { A shell "input swipe 200 $ty 960 $ty 250" | Out-Null; Start-Sleep -Milliseconds 350 }
}

# Settings -> Servers tab -> Servers panel. Returns nothing; leaves panel open.
function GotoServers {
    GotoSettings
    $srv = $null
    for ($i = 0; $i -lt 6; $i++) {
        $n = Grab
        $srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1
        if ($srv) { break }
        A shell "input swipe 950 744 150 744 350" | Out-Null
        Start-Sleep -Milliseconds 700
    }
    if (-not $srv) { Shot 'E2ECHK5554_NAV_abort'; throw 'Servers tab not found' }
    $p = CenterOf $srv.Line
    Tap $p[0] $p[1]
    Start-Sleep -Seconds 1
}

# Servers panel -> Add SSH server form (idempotent). v97b: verify the form really
# opened and retry, because a stray swipe can drag-dismiss the dialog silently.
function OpenAddForm {
    $n = Grab
    if ((Has $n 'Host or SSH command') -or (Has $n '^Authentication\t')) {
        Write-Host 'form already open'; return
    }
    for ($i = 0; $i -lt 3; $i++) {
        $n = Grab
        $add = $n | Select-String -Pattern '^Add SSH server\t' | Select-Object -First 1
        if (-not $add) { Shot 'E2ECHK5554_NAV_abort'; throw 'Add SSH server button not found' }
        $p = CenterOf $add.Line
        Tap $p[0] $p[1]
        Start-Sleep -Seconds 1
        $n = Grab
        if ((Has $n 'Host or SSH command') -or (Has $n '^Authentication\t')) {
            Write-Host "form open (attempt $($i + 1))"; return
        }
        Write-Host "  form did not open (attempt $($i + 1))"
    }
    Shot 'E2ECHK5554_NAV_abort'
    throw 'Add SSH server form did not open after 3 attempts'
}

# ---------- SSH form filling (label-anchored, adapted from v96 fillform.ps1) ----------
# v97b lesson (F14a, 18:10): a scroll gesture only works when the DOWN point is
# INSIDE the scrollable view. The form body ScrollView is [43,1343][1038,1725] but
# the old content-marker band was 1446..1759, i.e. it started BELOW the viewport on
# the footer -> 12 swipes, zero displacement.
#
# Second lesson (F14a rerun, 18:14): picking "the smallest ScrollView in the dump"
# picks the HOME session list [53,258][1027,847] that lives BEHIND the dialog, and
# swiping there/above the dialog card reads as drag-to-dismiss and CLOSES it.
# Correct rule: pick the smallest ScrollView that CONTAINS a form anchor, and never
# swipe at all when the form is not on screen.
function FormScrollRect {
    $xml = Join-Path $global:DIR 'ui_E2ECHK5554_nav_tmp.xml'
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
            if ($isAnchor -or ($nd.class -eq 'android.widget.EditText' -and $r[0] -eq 138)) {
                [void]$anchors.Add($r)
            }
        }
        foreach ($c in $nd.node) { $stack.Push($c) }
    }
    if ($anchors.Count -eq 0) { return $null }
    $best = $null; $bestArea = [int]::MaxValue
    foreach ($s in $scrolls) {
        foreach ($a in $anchors) {
            # s must fully contain a
            if ($a[0] -ge $s[0] -and $a[1] -ge $s[1] -and $a[2] -le $s[2] -and $a[3] -le $s[3]) {
                $area = ($s[2] - $s[0]) * ($s[3] - $s[1])
                if ($area -gt 0 -and $area -lt $bestArea) { $best = $s; $bestArea = $area }
                break
            }
        }
    }
    return $best
}

# The Add-SSH form is a NESTED scroll region; a swipe near the right edge (past the
# field edge 943 and the footer buttons 975) scrolls the panel without touching a
# field or reading as drag-to-dismiss.
function FormSwipe($n, [int]$dir = -1) {
    $r = FormScrollRect
    if (-not $r) {
        Write-Host '  form swipe SKIPPED (form body ScrollView not found - form may be closed; swiping here would drag-dismiss the dialog)'
        return
    }
    $x = $r[2] - 20
    $top = $r[1] + 30
    $bot = $r[3] - 30
    if (($bot - $top) -lt 100) { Write-Host "  form swipe SKIPPED (band too small: $top..$bot)"; return }
    if ($dir -ge 0) { $y1 = $top; $y2 = $bot } else { $y1 = $bot; $y2 = $top }
    Write-Host "  form swipe x=$x $y1 -> $y2 (scrollbody rect $($r[0]),$($r[1])..$($r[2]),$($r[3]))"
    A shell "input swipe $x $y1 $x $y2 400" | Out-Null
    Start-Sleep -Milliseconds 900
}

# form fields = EditTexts starting at x=138 (settings search box starts at x=96)
# v97b fix: the node table prints the FULL class "android.widget.EditText", so the
# old pattern '\tEditText\t' (tab BEFORE the word) never matched and Fields() always
# returned empty -> 12 dead ScrollToNear attempts. Match on the trailing tab only.
function Fields($n) {
    @($n | Select-String -Pattern 'EditText\t' | ForEach-Object { $_.Line } |
        Where-Object { $_ -match '\t\[138,' } |
        Sort-Object { [int]($_ -replace '^.*\[138,(\d+)\].*$', '$1') })
}
function RectOf($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        return ,@([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    }
    return $null
}
function FieldH($line) {
    $r = RectOf $line
    if (-not $r) { return 0 }
    return ($r[3] - $r[1])
}
# v97b: the old 140px 'below' window missed the private-key path field (it sits
# 158px under the "Private key" chip). Window widened to 300px and the NEAREST
# qualifying field is returned instead of the first match in table order.
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
function ScrollToNear($label, $what, $mode = 'below') {
    for ($i = 0; $i -lt 12; $i++) {
        $n = Grab
        $f = FieldNear $n $label $mode
        if ($f) { return ,@($f, $n) }
        $d = -1
        if ($i -ge 6) { $d = 1 }
        Write-Host "  '$what' not visible (attempt $($i + 1), dir $d)"
        # v97b (F14a, 18:20): with the IME up the form card is squeezed to the band
        # above the keyboard and its body ScrollView will NOT scroll at all (the
        # Authentication toggle is clipped behind the footer). TAB is the sanctioned
        # way to dismiss the IME (never BACK/ESC) and it instantly restores the full
        # card + a scrollable body. So every 3rd miss, collapse the IME and retry.
        if ($i -eq 2 -or $i -eq 7) { CollapseIme; $i = $i - 1; continue }
        FormSwipe $n $d
    }
    return $null
}
# Dismiss the soft keyboard with KEYCODE_TAB(61). Discipline: never BACK(4), and
# ESC(111) is forbidden inside Settings dialogs because it tears the dialog down.
function CollapseIme {
    Write-Host '  collapse IME (keyevent 61 TAB)'
    A shell "input keyevent 61" | Out-Null
    Start-Sleep -Milliseconds 900
}
function ScrollToPw($what) {
    for ($i = 0; $i -lt 6; $i++) {
        $n = Grab
        $f = Fields $n
        $hit = $f | Where-Object { $_ -match 'pw=true' -and (FieldH $_) -ge 50 } | Select-Object -First 1
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
        if ($foc) { Write-Host "    focused: $((($foc.Line -split "`t")[1]))"; return $true }
        Write-Host "  $what not focused; retrying"
        if ($anchor -ne '') {
            $line = FieldNear $n $anchor $mode
            if (-not $line) { FormSwipe $n -1; $n = Grab; $line = FieldNear $n $anchor $mode }
        } else {
            FormSwipe $n -1
            $n = Grab
            $line = (Fields $n | Where-Object { (FieldH $_) -ge 50 } | Select-Object -First 1)
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

# Fill the ALREADY-OPEN Add SSH server form. Keyboard stays UP (never ESC mid-form).
# -Auth Password: fills the password field.  -Auth PrivateKey: toggles the select,
# fills key path and (optionally, only if non-empty) the passphrase field.
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
    if (-not ((Has $n 'Host or SSH command') -or (Has $n '^Authentication\t'))) {
        throw 'Add SSH server form is not on screen'
    }
    # name: EditText directly ABOVE the always-visible Authentication label
    $res = ScrollToNear 'Authentication' 'name' 'above'
    if (-not $res) { throw 'name field unreachable' }
    if (-not (TapField $res[0] 'name' 'Authentication' 'above')) { throw 'name never focused' }
    TypeText $Name
    Start-Sleep -Milliseconds 700
    CollapseIme   # v97b: the card cannot scroll while the IME is up
    # host: second non-pw field above Authentication, bottom-up
    $hostLine = $null
    for ($i = 0; $i -lt 12; $i++) {
        $n = Grab
        $above = FieldsAboveAuth $n
        if ($above.Count -ge 2) { $hostLine = $above[$above.Count - 2][1]; break }
        $d = -1
        if ($i -ge 6) { $d = 1 }
        Write-Host "  'host' not visible (attempt $($i + 1), dir $d)"
        if ($i -eq 2 -or $i -eq 7) { CollapseIme; $i = $i - 1; continue }
        FormSwipe $n $d
    }
    if (-not $hostLine) { throw 'host field not found above name' }
    if (-not (TapField $hostLine 'host')) { throw 'host never focused' }
    TypeText $Target
    Start-Sleep -Milliseconds 700
    CollapseIme   # v97b: drop the IME before touching the Authentication chips
    if ($Auth -eq 'PrivateKey') {
        $n = Grab
        $tog = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1
        if ($tog) {
            $p = CenterOf $tog.Line; Tap $p[0] $p[1]; Start-Sleep -Seconds 1
            $n = Grab
            $opt = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
            if ($opt) { $p = CenterOf $opt.Line; Tap $p[0] $p[1]; Start-Sleep -Seconds 1 }
            else { throw 'Private key option not offered' }
        }
        # key path: plain field under its label
        $res = ScrollToNear 'Private key' 'keypath' 'below'
        if (-not $res) { throw 'key path field unreachable' }
        if (-not (TapField $res[0] 'keypath')) { throw 'keypath never focused' }
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
    Shot "E2ECHK5554_${Tag}_20_form_typed"
    $n = Grab
    Write-Host '--- form state ---'
    Fields $n | ForEach-Object { Write-Host "  FLD $_" }
    ($n | Select-String -Pattern '^Add server\t|^Cancel\t|^Password\t|^Private key\t|^Passphrase\t|Key') |
        ForEach-Object { Write-Host "  $_" }
    Shot "E2ECHK5554_${Tag}_21_form_ready"
}

# Tap "Add server" (resolved from a fresh dump) and return its coords via $script:LastSubmit.
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

# ---------- auth card answering (TIGHT block; no dumps between card-up and Continue) ----------
# Answer the kbdint / passphrase challenge in ONE uninterrupted block.
# FieldX/Y = credential field centre with IME UP; ContX/Y = Continue centre with IME UP.
# First run on a fresh build: execute with -Rehearse to LEARN those four numbers
# (rehearsal dumps may expire the challenge - that is fine, the card is cancelled).
# Samples the outcome after Continue and returns when 'Connected' shows up.
function Answer-Card {
    param(
        [Parameter(Mandatory = $true)][int]$ReconnectX,
        [Parameter(Mandatory = $true)][int]$ReconnectY,
        [string]$Secret = 'opencode',
        [int]$FieldX = 540,     # TODO rehearse on first run
        [int]$FieldY = 1641,    # TODO rehearse on first run
        [int]$ContX = 866,      # TODO rehearse on first run
        [int]$ContY = 1347,     # TODO rehearse on first run
        [string]$Tag = 'A',
        [int]$CardWaitMs = 2600,
        [int]$Samples = 10
    )
    ResetFaillock | Out-Null
    Write-Host 'faillock reset'
    Write-Host "RECONNECT @ $ReconnectX,$ReconnectY"
    A shell "input tap $ReconnectX $ReconnectY" | Out-Null
    Start-Sleep -Milliseconds $CardWaitMs
    # ---------------- TIGHT BLOCK: no dumps, no screenshots ----------------
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
    # ---------------- END TIGHT BLOCK ----------------
    Start-Sleep -Milliseconds 2500
    Shot "E2ECHK5554_${Tag}_40_result"
    Write-Host '--- outcome samples ---'
    for ($s = 1; $s -le $Samples; $s++) {
        $n = Nodes "E2ECHK5554_${Tag}_41_s$s"
        $bits = @()
        foreach ($pat in @('Connected', 'Could not establish', 'Authentication required', 'Connecting', 'Disconnected', 'left running', 'SSH authentication', 'passphrase', 'Passphrase', 'left to unlock')) {
            if (Has $n $pat) { $bits += $pat }
        }
        Write-Output ("  s{0,-2} {1}" -f $s, ($bits -join ' | '))
        if ($bits -contains 'Connected') { break }
    }
    Shot "E2ECHK5554_${Tag}_42_final"
}

# Learn the keyboard-up coordinates for Answer-Card. Dumps in this flow MAY expire
# the challenge; that is accepted because the card is cancelled at the end.
function Rehearse-Card {
    param([Parameter(Mandatory = $true)][int]$ReconnectX, [Parameter(Mandatory = $true)][int]$ReconnectY, [string]$Tag = 'R')
    ResetFaillock | Out-Null
    A shell "input tap $ReconnectX $ReconnectY" | Out-Null
    Start-Sleep -Milliseconds 2600
    $n = Grab   # card up, IME down
    Shot "E2ECHK5554_${Tag}_10_card_noime"
    Write-Host '--- card, IME down ---'
    ($n | Select-String -Pattern 'SSH authentication|Password:|Passphrase|Cancel|Continue|EditText') | ForEach-Object { Write-Host "  $($_.Line)" }
    $fld = $n | Select-String -Pattern 'EditText\t' | Select-Object -First 1
    $con = $n | Select-String -Pattern '^Continue\t' | Select-Object -First 1
    if (-not $fld -or -not $con) { Write-Host 'REHEARSE: card layout not recognised'; return }
    $pf = CenterOf $fld.Line
    Tap $pf[0] $pf[1]
    Start-Sleep -Milliseconds 1000
    $n = Grab   # IME up: card shifted
    Shot "E2ECHK5554_${Tag}_11_card_imeup"
    Write-Host '--- card, IME up (LEARNED coords for Answer-Card) ---'
    ($n | Select-String -Pattern 'SSH authentication|Password:|Passphrase|Cancel|Continue|EditText') | ForEach-Object { Write-Host "  $($_.Line)" }
    $fld2 = $n | Select-String -Pattern 'EditText\t' | Select-Object -First 1
    $con2 = $n | Select-String -Pattern '^Continue\t' | Select-Object -First 1
    if ($fld2) { $p = CenterOf $fld2.Line; Write-Host "LEARNED FieldX=$($p[0]) FieldY=$($p[1])" }
    if ($con2) { $p = CenterOf $con2.Line; Write-Host "LEARNED ContX=$($p[0]) ContY=$($p[1])" }
    # cancel the card with the LEARNED Cancel position (never ESC here)
    $can = $n | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
    if ($can) { $p = CenterOf $can.Line; Tap $p[0] $p[1]; Write-Host 'card cancelled' }
    ResetFaillock | Out-Null
}
