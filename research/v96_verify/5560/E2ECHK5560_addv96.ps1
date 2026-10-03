# E2ECHK5560_addv96.ps1 - build one Add-SSH entry using TAB focus-walking.
#
# WHY THIS EXISTS (v95's fillform.ps1/addentry.ps1 both fail on this build):
#   1. The form opens in a COMPACT state that renders only "Host or SSH command".
#      Nothing scrolls until a field is tapped (v95 REPORT section 6.4).
#   2. Once expanded, a swipe inside the form card does NOT scroll it. The reliable
#      mover is keyevent 61 (TAB): Compose moves focus to the next field, which
#      brings it (and its band) into view on its own.
#   3. A drag DOES scroll the panel, but only at x=1000 - a drag starting inside a
#      text field (x 138..943) is consumed as text selection, not as a scroll.
#   4. TAB past the last field walks focus OUT of the dialog into the home screen,
#      so never TAB more than the field count the form actually has.
#
# Field order: Host -> Name -> Authentication toggle -> [key path -> passphrase]
#                                                   \-> [password]
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Password = '',
    [string]$KeyPath = '',
    [string]$Passphrase = '',
    [ValidateSet('Password', 'Private key')][string]$Auth = 'Password',
    [string]$Tag = 'T',
    [switch]$NoSubmit
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5560_run.ps1" -Dev emulator-5560

function Grab { Nodes 'E2ECHK5560_nav_tmp' }
function B($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
        $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}
function FocusLine($n) { $n | Select-String -Pattern 'foc=true' | Select-Object -First 1 }
function Tab { A shell "input keyevent 61" | Out-Null; Start-Sleep -Milliseconds 900 }

$n = Grab
if (-not (($n | Select-String -Pattern '^Host or SSH command\t' -Quiet))) {
    Write-Host 'ABORT: Add SSH form is not on screen'
    ($n | Select-String -Pattern 'EditText|Add server|Close|^Servers\t') |
        Select-Object -First 10 | ForEach-Object { Write-Host "   $($_.Line)" }
    exit 2
}

# --- 1. wake the compact form: tap the host field under its own label ---
$hostField = $null
for ($i = 0; $i -lt 4; $i++) {
    $lab = Grab | Select-String -Pattern '^Host or SSH command\t' | Select-Object -First 1
    if ($lab -and $lab.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $ly = [int]$Matches[2]
        $hostField = Grab | Select-String -Pattern '\tEditText\t' |
            Where-Object { $_ -match '\[138,(\d+)\]' -and [int]$Matches[1] -gt $ly -and [int]$Matches[1] -lt ($ly + 200) } |
            Select-Object -First 1
    }
    if ($hostField) { break }
    Tap 540 1639   # compact-state host field position
    Start-Sleep -Seconds 1
}
if (-not $hostField) { Write-Host 'ABORT: host field not found'; exit 2 }
$p = B $hostField.Line
Write-Host "tap host @ $($p[0]),$($p[1])"
Tap $p[0] $p[1]
Start-Sleep -Seconds 1
TypeText $Target
Start-Sleep -Milliseconds 700

# --- 2. TAB -> name, type it ---
Tab
$f = FocusLine (Grab)
Write-Host "after TAB1 focus: $($f.Line)"
if (-not ($f.Line -match 'pw=true')) { TypeText $Name }
Start-Sleep -Milliseconds 700

# --- 3. TAB -> auth toggle; only switch when the entry is not already on it ---
Tab
$n = Grab
$tog = $n | Select-String -Pattern "^$(if($Auth -eq 'Password'){'Password'})?\t" | Out-Null
$cur = $n | Select-String -Pattern '^Private key\t\[' | Select-Object -First 1
$wantPrivate = ($Auth -eq 'Private key')
$isPrivate = [bool]$cur
if ($wantPrivate -ne $isPrivate) {
    $t = Grab | Select-String -Pattern "^$(if($isPrivate){'Private key'}else{'Password'})\t" | Select-Object -First 1
    if ($t) {
        $p = B $t.Line
        Write-Host "tap auth toggle '$($t.Line.Split("`t")[0])' @ $($p[0]),$($p[1])"
        Tap $p[0] $p[1]
        Start-Sleep -Seconds 1
        $o = Grab | Select-String -Pattern "^$Auth`t" | Select-Object -First 1
        if ($o) {
            $p = B $o.Line
            Write-Host "select auth '$Auth' @ $($p[0]),$($p[1])"
            Tap $p[0] $p[1]
            Start-Sleep -Seconds 1
        } else { Write-Host "WARN: auth option '$Auth' not in menu" }
    } else { Write-Host 'WARN: auth toggle not found' }
}

# --- 4. credential field(s), each one TAB away ---
if ($Auth -eq 'Password') {
    Tab
    $f = FocusLine (Grab)
    Write-Host "after auth TAB focus: $($f.Line)"
    if ($f -and ($f.Line -match 'pw=true')) { TypeText $Password }
} else {
    Tab
    $f = FocusLine (Grab)
    Write-Host "keypath focus: $($f.Line)"
    TypeText $KeyPath
    Tab
    $f = FocusLine (Grab)
    Write-Host "passphrase focus: $($f.Line)"
    TypeText $Passphrase
}
Start-Sleep -Milliseconds 700

Shot "E2ECHK5560_${Tag}_30_form_typed"
$n = Grab
Write-Host '--- form state ---'
($n | Select-String -Pattern '\tEditText\t|^Add server\t|^Cancel\t|^Authentication\t|^Private key\t|^Password\t|Key file path|Passphrase') |
    ForEach-Object { Write-Host "  $($_.Line)" }

if ($NoSubmit) { Write-Host 'NoSubmit - stop'; exit 0 }

$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if (-not $sub) {
    Write-Host 'ABORT: Add server button not visible'
    ($n | Select-String -Pattern 'EditText|Add server|Cancel') | Select-Object -First 10 |
        ForEach-Object { Write-Host "   $($_.Line)" }
    exit 2
}
$p = B $sub.Line
Write-Host "SUBMIT @ $($p[0]),$($p[1])"
Tap $p[0] $p[1]
Start-Sleep -Seconds 4
Shot "E2ECHK5560_${Tag}_31_after_submit"
$n = Grab
Write-Host '--- state after submit ---'
($n | Select-String -Pattern 'Verify|SSH authentication|Password:|Passphrase|^Cancel\t|^Continue\t|Trust|Connected|Connecting|ailed|required|^Connect\t|Reconnect|Disconnect|E2EC5560') |
    Select-Object -First 25 | ForEach-Object { Write-Host "  $($_.Line)" }
