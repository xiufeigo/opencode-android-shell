# E2ECHK5558_fillform.ps1 - fill an ALREADY-OPEN Add SSH server form, keyboard kept UP
# (ESC mid-form is unsafe: `input text` can auto-hide the IME, and the late ESC then
#  dismisses the whole Settings dialog.)
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Password = 'wrongpw',
    [string]$KeyPath = '',
    [string]$Passphrase = '',
    [switch]$PickPrivateKey,
    [string]$Tag = 'R'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5558_nav.ps1"

function Nodes { python "$PSScriptRoot\E2ECHK5558_nodes.py" "$DIR\ui_E2ECHK5558_nav_tmp.xml" }
function Grab { Ui 'E2ECHK5558_nav_tmp'; Nodes }
function B($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}
# form fields = EditTexts starting at x=138 (the settings search box starts at x=96)
function Fields($n) {
    @($n | Select-String -Pattern '\tEditText\t' | ForEach-Object { $_.Line } |
        Where-Object { $_ -match '\t\[138,' } | Sort-Object { [int]($_ -replace '^.*\[138,(\d+)\].*$', '$1') })
}
function TapLine($line, $what) {
    $p = B $line
    if (-not $p) { Write-Host "  FAIL bounds for $what"; return $false }
    Write-Host "  tap $what @ $($p[0]),$($p[1])   <<$(($line -split "`t")[0])>>"
    Tap $p[0] $p[1]
    Start-Sleep -Milliseconds 900
    return $true
}
# tap a form field and confirm it actually took focus (layout shifts after the IME opens)
function TapField($line, $what) {
    for ($i = 0; $i -lt 3; $i++) {
        TapLine $line $what | Out-Null
        $n = Grab
        $foc = $n | Select-String -Pattern '\tEditText\t' | Where-Object { $_ -match 'foc=true' } | Select-Object -First 1
        if ($foc) { return $true }
        Write-Host "  $what not focused; re-scrolling and retrying"
        FormSwipe $n
        $n = Grab
        $line = $n | Select-String -Pattern '\tEditText\t' | Where-Object { $_ -match "\t\[138," } |
            Sort-Object { [int]($_ -replace '^.*\[138,(\d+)\].*$', '$1') } |
            Where-Object { (FieldH $_) -ge 50 } | Select-Object -First 1
        if (-not $line) { return $false }
        $line = $line.Line
    }
    return $false
}

function FieldH($line) {
    if ($line -match '\[138,(\d+)\]\[(\d+),(\d+)\]') { return ([int]$Matches[3] - [int]$Matches[1]) }
    return 0
}
# The form's visible band shrinks when the IME is up, so a fixed swipe would start on
# the keyboard. Derive the swipe band from the pinned footer (Cancel / Add server) top.
function FormSwipe($n) {
    $ft = $n | Select-String -Pattern '^Add server\t|^Cancel\t' | Select-Object -First 1
    $fy = 1780
    if ($ft -and $ft.Line -match '\[(\d+),(\d+)\]') { $fy = [int]$Matches[2] }
    $y1 = $fy - 90
    $y2 = $y1 - 420
    if ($y2 -lt 700) { $y2 = 700 }
    & $ADB -s emulator-5558 shell "input swipe 540 $y1 540 $y2 350" 2>&1 | Out-Null
    Start-Sleep -Milliseconds 900
}

# scroll the form until the form field at $idx is fully visible (>=50px tall)
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
# scroll the form until a pw=true field is fully visible
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

$n = Grab
# NB: on an array, -notmatch FILTERS rather than returning a boolean - use Select-String.
$isForm = ($n | Select-String -Pattern '^Host or SSH command\t' | Select-Object -First 1) -or
          ($n | Select-String -Pattern '^Authentication\t' | Select-Object -First 1)
if (-not $isForm) {
    Write-Host 'ABORT: Add SSH server form is not on screen'
    ($n | Select-String -Pattern 'EditText|Search settings|Add server|Close') | Select-Object -First 8 |
        ForEach-Object { Write-Host "    $($_.Line)" }
    exit 2
}

# --- host ---
$f = Fields $n
if ($f.Count -lt 1) { Write-Host 'ABORT: no host field'; exit 2 }
TapField $f[0] 'host' | Out-Null
TypeText $Target
Start-Sleep -Milliseconds 500

# --- name: form field index 1 (index 0 is the host we just typed into) ---
$res = ScrollToIdx 1 'name'
if (-not $res) { Write-Host 'ABORT: name field'; exit 2 }
TapField $res[0] 'name' | Out-Null
TypeText $Name
Start-Sleep -Milliseconds 500

# --- auth: switch to Private key if asked ---
if ($PickPrivateKey) {
    $n = Grab
    $tog = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1
    if ($tog) { TapLine $tog.Line 'auth-toggle' | Out-Null; Start-Sleep -Seconds 1 }
    $n = Grab
    $opt = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if ($opt) { TapLine $opt.Line 'auth=PrivateKey' | Out-Null; Start-Sleep -Seconds 1 }
    else { Write-Host 'WARN: Private key option not found'; ($n | Select-String -Pattern 'Password|Private key') | Select-Object -First 6 | ForEach-Object { Write-Host "   $($_.Line)" } }
}

# --- credential field (pw=true) ---
$res = ScrollToPw 'credential'
if (-not $res) { Write-Host 'ABORT: credential field'; exit 2 }
$n = $res[1]
TapField $res[0] 'credential' | Out-Null
if ($KeyPath -ne '') { TypeText $KeyPath }
elseif ($Password -ne '') { TypeText $Password }

Shot "E2ECHK5558_${Tag}_20_form_typed"
$n = Grab
Write-Host '--- form state (keyboard up) ---'
Fields $n | ForEach-Object { Write-Host "  FLD $_" }
($n | Select-String -Pattern '^Add server\t|^Cancel\t|^Password\t|^Private key\t|^Passphrase\t|key|Key') |
    ForEach-Object { Write-Host "  $_" }
Shot "E2ECHK5558_${Tag}_21_form_ready"
