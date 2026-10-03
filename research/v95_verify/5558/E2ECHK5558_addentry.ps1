# E2ECHK5558_addentry.ps1
#   -Name <entry name>  -Auth <Password|Key>  -Password <pw>  -KeyPath <path>
#   -Passphrase <pp>   -Tag <evidence tag>  -NoSubmit
param(
    [string]$Name = 'V95R',
    [string]$Auth = 'Password',
    [string]$Password = 'wrongpw',
    [string]$KeyPath = '',
    [string]$Passphrase = '',
    [string]$Tag = 'R',
    [switch]$NoSubmit
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5558_nav.ps1"

function Nodes { python "$PSScriptRoot\E2ECHK5558_nodes.py" "$DIR\ui_E2ECHK5558_nav_tmp.xml" }
function Grab { Ui 'E2ECHK5558_nav_tmp'; Nodes }

function B($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
        $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}
function Y0($line) { if ($line -match '\[(\d+),(\d+)\]') { [int]$Matches[2] } else { -1 } }

function TabY($n) {
    # tab strip sits ~184px below the "Search settings" box; that box is always present
    $sb = $n | Select-String -Pattern 'Search settings\t' | Select-Object -First 1
    if ($sb -and $sb.Line -match '\[(\d+),(\d+)\]') { return ([int]$Matches[2] + 184) }
    return 744
}

function ClearSearch($n) {
    $sb = $n | Select-String -Pattern '\t\[96,[0-9]+\]\[[0-9]+,[0-9]+\]\tpw=\w+\tEditText$' | Select-Object -First 1
    if (-not $sb) { return $false }
    $txt = ($sb.Line -split "`t")[0]
    if ($txt.Trim() -eq '') { return $false }
    $c = $n | Select-String -Pattern '^Clear search\t' | Select-Object -First 1
    if ($c) { $p = B $c.Line; Write-Host "clear settings search (had '$txt')"; Tap $p[0] $p[1]; Start-Sleep -Seconds 1; return $true }
    return $false
}

function OpenForm {
    HideKb
    $n = Grab
    if ($n -notmatch 'Search settings') {
        $s = $n | Select-String -Pattern '^Settings\t\[[0-9]+,2[0-9]{3}\]' | Select-Object -First 1
        if ($s) { $p = B $s.Line; if ($p) { Write-Host "open Settings @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] } }
        Start-Sleep -Seconds 1
    }
    $n = Grab
    ClearSearch $n | Out-Null
    $n = Grab
    $ty = TabY $n
    for ($i = 0; $i -lt 8; $i++) {
        & $ADB -s emulator-5558 shell "input swipe 200 $ty 980 $ty 250" 2>&1 | Out-Null
        Start-Sleep -Milliseconds 400
    }
    $srv = $null
    for ($i = 0; $i -lt 8; $i++) {
        HideKb
        $n = Grab
        $ty = TabY $n
        $srv = $n | Select-String -Pattern '^Servers\t\[[0-9]+,7\d\d\]' | Select-Object -First 1
        if ($srv) { break }
        & $ADB -s emulator-5558 shell "input swipe 950 $ty 150 $ty 350" 2>&1 | Out-Null
        Start-Sleep -Milliseconds 700
    }
    HideKb
    $n = Grab
    $srv = $n | Select-String -Pattern '^Servers\t\[[0-9]+,7\d\d\]' | Select-Object -First 1
    if ($srv) { $p = B $srv.Line; Write-Host "tab Servers @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] }
    else { Write-Host 'WARN Servers tab not found' }
    Start-Sleep -Seconds 1
    $n = Grab
    $add = $n | Select-String -Pattern '^Add SSH server\t' | Select-Object -First 1
    if ($add) { $p = B $add.Line; Write-Host "AddSSH @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] }
    else { Write-Host 'WARN AddSSH not found' }
    Start-Sleep -Seconds 1
}

function AssertForm {
    $n = Grab
    $has = ($n -match '^Host or SSH command\t') -or ($n -match '^Authentication\t') -or ($n -match '^Add server\t')
    if (-not $has) {
        Write-Host 'ABORT: SSH form is not on screen; refusing to type into a random field.'
        Write-Host '  markers seen:'
        ($n | Select-String -Pattern 'Search settings|Search sessions|EditText|Add server|Close') |
            Select-Object -First 8 | ForEach-Object { Write-Host "    $($_.Line)" }
        exit 2
    }
    return $n
}

# Fill the Nth (0-based) EditText in the form, or the pw=true one
function FillEdit($text, $label, $idx = -1) {
    $n = AssertForm
    # form fields start at x=138; the settings search box starts at x=96 (and its
    # hint text disappears once typed into, so it must be excluded geometrically)
    $ets = @($n | Select-String -Pattern '\tEditText\t' | ForEach-Object { $_.Line } |
             Where-Object { $_ -notmatch '\t\[96,' } | Sort-Object { Y0 $_ })
    $tgt = $null
    if ($idx -ge 0 -and $ets.Count -gt $idx) { $tgt = $ets[$idx] }
    else { $tgt = $ets | Where-Object { $_ -match 'pw=true' } | Select-Object -First 1 }
    if (-not $tgt) {
        Write-Host "WARN edit '$label' not found (have $($ets.Count))"
        $ets | ForEach-Object { Write-Host "    have: $_" }
        return $false
    }
    $p = B $tgt
    Write-Host "  fill '$label' @ $($p[0]),$($p[1])  <<$($tgt.Split("`t")[0])>>"
    Tap $p[0] $p[1]
    Start-Sleep -Milliseconds 900
    if ($text -ne '') { TypeText $text }
    HideKb
    return $true
}

function PickAuth($want) {
    # closed state shows current auth as a TextView with a caret; tap it, then pick from the menu
    $n = Grab
    $closed = $n | Select-String -Pattern "^$want\t\[[0-9]+,1[0-9]{3}\]" | Select-Object -First 1
    if ($closed) {
        $p = B $closed.Line
        Write-Host "  auth toggle @ $($p[0]),$($p[1])"
        Tap $p[0] $p[1]
        Start-Sleep -Seconds 1
        $n = Grab
    }
    $opt = $n | Select-String -Pattern "^$want\t" | Select-Object -First 1
    if ($opt) { $p = B $opt.Line; Write-Host "  auth option '$want' @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] }
    else {
        Write-Host "  WARN auth option '$want' not found. menu shows:"
        ($n | Select-String -Pattern 'Password|Private key|Authenticat') | Select-Object -First 8 | ForEach-Object { Write-Host "    $_" }
        return $false
    }
    Start-Sleep -Seconds 1
    return $true
}

function ScrollDown { & $ADB -s emulator-5558 shell "input swipe 540 1650 540 1150 350" 2>&1 | Out-Null; Start-Sleep -Milliseconds 800 }

# ---------------- main ----------------
OpenForm
Shot "E2ECHK5558_${Tag}_10_form_open"

FillEdit 'ssh -p 2222 sshtest@127.0.0.1' 'host' 0 | Out-Null
ScrollDown
FillEdit $Name 'name' 1 | Out-Null
ScrollDown
PickAuth $Auth | Out-Null
ScrollDown
Shot "E2ECHK5558_${Tag}_11_form_auth"

if ($Auth -eq 'Key') {
    FillEdit $KeyPath 'keypath' 2 | Out-Null
    ScrollDown
    $n = Grab
    $pp = $n | Select-String -Pattern 'Passphrase' | Select-Object -First 1
    if ($pp) { FillEdit $Passphrase 'passphrase' 3 | Out-Null }
    else { Write-Host '  (no Passphrase field present)' }
} else {
    FillEdit $Password 'password' | Out-Null
}

Shot "E2ECHK5558_${Tag}_12_before_submit"
$n = Grab
Write-Host '--- fields before submit ---'
($n | Select-String -Pattern '\tEditText\t') | ForEach-Object { Write-Host "  $($_.Line)" }
($n | Select-String -Pattern 'Add server|Cancel') | ForEach-Object { Write-Host "  $($_.Line)" }

if ($NoSubmit) { Write-Host 'NoSubmit - stop'; exit 0 }

$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if ($sub) { $p = B $sub.Line; Write-Host "SUBMIT @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] }
else { Write-Host 'WARN Add server not found' }
Start-Sleep -Seconds 4
Shot "E2ECHK5558_${Tag}_13_after_submit"
$n = Grab
Write-Host '--- state after submit ---'
($n | Select-String -Pattern 'Verify|SSH authentication|Password:|Cancel|Continue|Trust|Connected|Connecting|ailed|required|EditText|Passphrase|Key:') | ForEach-Object { Write-Host "  $($_.Line)" }
