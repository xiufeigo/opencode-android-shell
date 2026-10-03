# E2ECHK5560_fillform.ps1 - fill an ALREADY-OPEN Add SSH server form, keyboard kept UP
# Adapted from E2ECHK5558_fillform.ps1.
# ESC mid-form is unsafe: it dismisses the whole Settings dialog. So we never call
# HideKb inside the form; the IME stays up and the scroll band is derived from the
# pinned footer (Cancel / Add server) top edge.
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Password = 'wrongpw',
    [string]$KeyPath = '',
    [string]$Passphrase = '',
    [switch]$PickPrivateKey,
    [switch]$Submit,
    [string]$Tag = 'C'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5560_run.ps1" -Dev emulator-5560

function Grab { Nodes 'E2ECHK5560_nav_tmp' }
function B($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}
# form fields = EditTexts starting at x=138 (settings search box starts at x=96)
function Fields($n) {
    @($n | Select-String -Pattern '\tEditText\t' | ForEach-Object { $_.Line } |
        Where-Object { $_ -match '\t\[138,' } |
        Sort-Object { [int]($_ -replace '^.*\[138,(\d+)\].*$', '$1') })
}
function FieldH($line) {
    if ($line -match '\[138,(\d+)\]\[(\d+),(\d+)\]') { return ([int]$Matches[3] - [int]$Matches[1]) }
    return 0
}
function TapLine($line, $what) {
    $p = B $line
    if (-not $p) { Write-Host "  FAIL bounds for $what"; return $false }
    Write-Host "  tap $what @ $($p[0]),$($p[1])   <<$(($line -split "`t")[0])>>"
    Tap $p[0] $p[1]
    Start-Sleep -Milliseconds 900
    return $true
}
function TapField($line, $what, $anchor = '', $mode = 'below') {
    for ($i = 0; $i -lt 3; $i++) {
        TapLine $line $what | Out-Null
        $n = Grab
        $foc = $n | Select-String -Pattern '\tEditText\t' | Where-Object { $_ -match 'foc=true' } | Select-Object -First 1
        if ($foc) { Write-Host "    focused: bounds=$((($foc.Line -split "`t")[1])) pw=$((($foc.Line -split "`t")[2]))"; return $true }
        Write-Host "  $what not focused; re-resolving and retrying"
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
# The Add-SSH form is a NESTED scroll region rendered inline in the Settings panel,
# starting around y=1406 in its unscrolled state - not at the top of the dialog. A swipe
# starting at y=760 lands on the tab strip / panel background and the form never scrolls.
# So the band is derived from the topmost visible form marker and the pinned footer.
# $Dir: -1 = reveal later content (content moves up), +1 = reveal earlier content.
function FormSwipe($n, [int]$Dir = -1) {
    $ft = $n | Select-String -Pattern '^Add server\t|^Cancel\t' | Select-Object -First 1
    $fy = 1780
    if ($ft -and $ft.Line -match '\[(\d+),(\d+)\]') { $fy = [int]$Matches[2] }
    $bot = $fy - 20
    # Form markers all start at x1=106; the Servers panel's own "Add SSH server" button
    # starts at x1=544, so it must be excluded or it drags the band up out of the form.
    $markers = $n | Select-String -Pattern '^Add SSH server\t\[106,|^Host or SSH command\t\[106,|^Server name \(optional\)\t\[106,|^Authentication\t\[106,|^Private key\t\[106,|^Passphrase\t\[106,'
    $top = 99999
    foreach ($m in $markers) {
        if ($m.Line -match '\[106,(\d+)\]') {
            $c = [int]$Matches[1] + 40
            if ($c -lt $top) { $top = $c }
        }
    }
    if ($top -eq 99999) { $top = 760 }
    if ($top -lt 700) { $top = 700 }
    if (($bot - $top) -lt 120) { $top = $bot - 120 }
    if ($Dir -ge 0) { $y1 = $top; $y2 = $bot } else { $y1 = $bot; $y2 = $top }
    Write-Host "  form swipe $y1 -> $y2 (band $top..$bot, footer top $fy)"
    A shell "input swipe 540 $y1 540 $y2 400" | Out-Null
    Start-Sleep -Milliseconds 900
}
function ScrollToIdx($idx, $what) {
    for ($i = 0; $i -lt 6; $i++) {
        $n = Grab
        $f = Fields $n
        if ($f.Count -gt $idx -and (FieldH $f[$idx]) -ge 50) { return ,@($f[$idx], $n) }
        FormSwipe $n
    }
    Write-Host "  FAIL: '$what' (idx $idx) not reachable"
    return $null
}
function ScrollToPw($what) {
    for ($i = 0; $i -lt 6; $i++) {
        $n = Grab
        $f = Fields $n
        $hit = $f | Where-Object { $_ -match 'pw=true' -and (FieldH $_) -ge 50 } | Select-Object -First 1
        if ($hit) { return ,@($hit, $n) }
        FormSwipe $n
    }
    Write-Host "  FAIL: '$what' (pw=true) not reachable"
    return $null
}
# Label-anchored field lookup. Index-based lookup was fragile: the Server name field is
# clipped by the pinned footer in the unscrolled form, so it never met the >=50px height
# gate and the typed name leaked into the still-focused host field.
# $Mode 'below' = EditText just under a label;  'above' = EditText just over a label.
function FieldNear($n, $label, $mode = 'below') {
    $lab = $n | Select-String -Pattern ([regex]::Escape($label)) | Select-Object -First 1
    if (-not $lab) { return $null }
    if ($lab.Line -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return $null }
    $ly = [int]$Matches[2]
    if ($mode -eq 'below') {
        return (Fields $n | Where-Object {
            ($_ -match '\[138,(\d+)\]') -and ([int]$Matches[1] -gt $ly) -and ([int]$Matches[1] -lt ($ly + 140)) -and (FieldH $_) -ge 50
        } | Select-Object -First 1)
    }
    return (Fields $n | Where-Object {
        ($_ -match '\[138,(\d+)\]\[(\d+),(\d+)\]') -and ([int]$Matches[3] -le $ly) -and ([int]$Matches[3] -gt ($ly - 150)) -and (FieldH $_) -ge 50
    } | Select-Object -First 1)
}
# A missing anchor could be scrolled off EITHER end, so alternate: first reveal later
# content (form usually starts at the top), then reveal earlier content.
function ScrollToNear($label, $what, $mode = 'below') {
    for ($i = 0; $i -lt 12; $i++) {
        $n = Grab
        $f = FieldNear $n $label $mode
        if ($f) { return ,@($f, $n) }
        $d = -1
        if ($i -ge 6) { $d = 1 }
        Write-Host "  '$what' not visible yet (swipe dir $d, attempt $($i + 1))"
        FormSwipe $n $d
    }
    Write-Host "  FAIL: '$what' near '$label' not reachable"
    return $null
}
# Fully-visible non-pw fields sitting above the Authentication label, bottom-up.
function FieldsAboveAuth($n) {
    $auth = $n | Select-String -Pattern '^Authentication\t' | Select-Object -First 1
    if (-not $auth) { return @() }
    if ($auth.Line -notmatch '\[(\d+),(\d+)\]') { return @() }
    $ay = [int]$Matches[2]
    $out = @()
    foreach ($f in (Fields $n)) {
        if ($f -match '\[138,(\d+)\]\[(\d+),(\d+)\]' -and [int]$Matches[3] -le $ay -and (FieldH $f) -ge 50 -and $f -notmatch 'pw=true') {
            $out += ,@([int]$Matches[1], $f)
        }
    }
    return @($out | Sort-Object { $_[0] })
}

$n = Grab
$isForm = ($n | Select-String -Pattern '^Host or SSH command\t' | Select-Object -First 1) -or
          ($n | Select-String -Pattern '^Authentication\t' | Select-Object -First 1)
if (-not $isForm) {
    Write-Host 'ABORT: Add SSH server form is not on screen'
    ($n | Select-String -Pattern 'EditText|Search settings|Add server|Close') | Select-Object -First 8 |
        ForEach-Object { Write-Host "    $($_.Line)" }
    exit 2
}

# --- name: the field directly ABOVE the always-visible "Authentication" label ---
# (its own "Server name (optional)" label is often clipped out of the dump)
$res = ScrollToNear 'Authentication' 'name' 'above'
if (-not $res) { Write-Host 'ABORT: name field'; exit 2 }
if (-not (TapField $res[0] 'name' 'Authentication' 'above')) { Write-Host 'ABORT: name never focused'; exit 2 }
TypeText $Name
Start-Sleep -Milliseconds 700

# --- host: the field directly ABOVE the name field ---
$hostLine = $null
for ($i = 0; $i -lt 12; $i++) {
    $n = Grab
    $above = FieldsAboveAuth $n
    if ($above.Count -ge 2) { $hostLine = $above[($above.Count - 2)][1]; break }
    $d = -1
    if ($i -ge 6) { $d = 1 }
    Write-Host "  only $($above.Count) non-pw field(s) above Authentication (attempt $($i + 1), dir $d)"
    FormSwipe $n $d
}
if (-not $hostLine) { Write-Host 'ABORT: host field not found above name'; exit 2 }
if (-not (TapField $hostLine 'host')) { Write-Host 'ABORT: host never focused'; exit 2 }
TypeText $Target
Start-Sleep -Milliseconds 700

# guard: the focused field must be ABOVE the Authentication label, and the field just
# above Authentication (the name) must be the one we typed the name into
$n = Grab
$above = FieldsAboveAuth $n
Write-Host "  fields above Authentication: $($above.Count)"
if ($above.Count -ge 1) { Write-Host "  name field now at $($above[$above.Count-1][1])" }

# --- auth ---
if ($PickPrivateKey) {
    $n = Grab
    $tog = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1
    if ($tog) { TapLine $tog.Line 'auth-toggle' | Out-Null; Start-Sleep -Seconds 1 }
    $n = Grab
    $opt = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if ($opt) { TapLine $opt.Line 'auth=PrivateKey' | Out-Null; Start-Sleep -Seconds 1 }
    else { Write-Host 'WARN: Private key option not found'; ($n | Select-String -Pattern 'Password|Private key') | Select-Object -First 6 | ForEach-Object { Write-Host "   $($_.Line)" } }
    # --- key path (plain text field under its label) ---
    $res = ScrollToNear 'Private key' 'keypath' 'below'
    if ($res) { TapField $res[0] 'keypath' | Out-Null; if ($KeyPath -ne '') { TypeText $KeyPath } }
    else { Write-Host 'ABORT: key path field' }
    # --- passphrase (pw=true) ---
    $res = ScrollToPw 'passphrase'
    if ($res) { TapField $res[0] 'passphrase' | Out-Null; if ($Passphrase -ne '') { TypeText $Passphrase } }
    else { Write-Host 'ABORT: passphrase field' }
} else {
    # --- password (pw=true) ---
    $res = ScrollToPw 'password'
    if (-not $res) { Write-Host 'ABORT: credential field'; exit 2 }
    TapField $res[0] 'password' | Out-Null
    if ($Password -ne '') { TypeText $Password }
}

Shot "E2ECHK5560_${Tag}_20_form_typed"
$n = Grab
Write-Host '--- form state (keyboard up) ---'
Fields $n | ForEach-Object { Write-Host "  FLD $_" }
($n | Select-String -Pattern '^Add server\t|^Cancel\t|^Password\t|^Private key\t|^Passphrase\t|Key') |
    ForEach-Object { Write-Host "  $_" }
Shot "E2ECHK5560_${Tag}_21_form_ready"

if (-not $Submit) { Write-Host 'NoSubmit - stop'; exit 0 }

$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if (-not $sub) {
    Write-Host 'ABORT: Add server button not visible'
    ($n | Select-String -Pattern '\tEditText\t|Add server|Cancel') | ForEach-Object { Write-Host "  $($_.Line)" }
    exit 2
}
$p = B $sub.Line
Write-Host "SUBMIT @ $($p[0]),$($p[1])"
Tap $p[0] $p[1]
Start-Sleep -Seconds 4
Shot "E2ECHK5560_${Tag}_22_after_submit"
$n = Grab
Write-Host '--- state after submit ---'
($n | Select-String -Pattern 'Verify|SSH authentication|Password:|Cancel|Continue|Trust|Connected|Connecting|ailed|required|EditText|Passphrase|Key|Reconnect|Disconnect') |
    ForEach-Object { Write-Host "  $($_.Line)" }
