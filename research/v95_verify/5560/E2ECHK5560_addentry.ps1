# E2ECHK5560_addentry.ps1  - adapted from E2ECHK5558_addentry.ps1
#   -Name <entry name>  -Auth <Password|Private key>  -Password <pw>
#   -KeyPath <path>  -Passphrase <pp>  -Tag <evidence tag>  -NoSubmit
#
# FIX vs the 5558 original: every adb call goes through A (from E2ECHK5560_run.ps1).
# The 5558 script hardcoded `-s emulator-5558` in ScrollDown / OpenForm.
# Also: the auth dropdown option text is `Private key` (not `Key`) in this build.
param(
    [string]$Name = 'E2EC5560C',
    [string]$Auth = 'Password',
    [string]$Password = 'wrongpw',
    [string]$KeyPath = '',
    [string]$Passphrase = '',
    [string]$Tag = 'C',
    [switch]$NoSubmit,
    [switch]$FormOnly
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5560_run.ps1" -Dev emulator-5560

function Grab($n) { Nodes $n }

function B($line) {
    if ($line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
        $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return ,@([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}
function Y0($line) { if ($line -match '\[(\d+),(\d+)\]') { [int]$Matches[2] } else { -1 } }
function Ctr($line) { $p = B $line; if ($p) { return "$($p[0]),$($p[1])" } else { return "?" } }

function OpenForm {
    HideKb
    $n = Grab 'E2ECHK5560_nav_tmp'
    if (($n -join "`n") -notmatch 'Search settings') {
        $s = $n | Select-String -Pattern '^Settings\t\[[0-9]+,2[0-9]{3}\]' | Select-Object -First 1
        if ($s) { $p = B $s.Line; Write-Host "open Settings @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] }
        Start-Sleep -Seconds 1
    }
    $n = Grab 'E2ECHK5560_nav_tmp'
    # tab strip sits ~184px below the "Search settings" box
    $sb = $n | Select-String -Pattern 'Search settings\t' | Select-Object -First 1
    $ty = 744
    if ($sb -and $sb.Line -match '\[(\d+),(\d+)\]') { $ty = [int]$Matches[2] + 184 }
    for ($i = 0; $i -lt 8; $i++) { A shell "input swipe 200 $ty 980 $ty 250" | Out-Null; Start-Sleep -Milliseconds 400 }
    $srv = $null
    for ($i = 0; $i -lt 8; $i++) {
        HideKb
        $n = Grab 'E2ECHK5560_nav_tmp'
        $srv = $n | Select-String -Pattern '^Servers\t\[[0-9]+,7\d\d\]' | Select-Object -First 1
        if ($srv) { break }
        A shell "input swipe 950 744 150 744 350" | Out-Null
        Start-Sleep -Milliseconds 700
    }
    HideKb
    $n = Grab 'E2ECHK5560_nav_tmp'
    $srv = $n | Select-String -Pattern '^Servers\t\[[0-9]+,7\d\d\]' | Select-Object -First 1
    if ($srv) { $p = B $srv.Line; Write-Host "tab Servers @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] }
    else { Write-Host 'WARN Servers tab not found' }
    Start-Sleep -Seconds 1
    $n = Grab 'E2ECHK5560_nav_tmp'
    $add = $n | Select-String -Pattern '^Add SSH server\t' | Select-Object -First 1
    if ($add) { $p = B $add.Line; Write-Host "AddSSH @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] }
    else { Write-Host 'WARN AddSSH not found' }
    Start-Sleep -Seconds 1
}

function AssertForm($n) {
    $j = $n -join "`n"
    if (($j -notmatch 'Host or SSH command') -and ($j -notmatch 'Authentication') -and ($j -notmatch 'Add server')) {
        Write-Host 'ABORT: SSH form is not on screen; refusing to type into a random field.'
        ($n | Select-String -Pattern 'Search settings|EditText|Add server|Close') |
            Select-Object -First 8 | ForEach-Object { Write-Host "    $($_.Line)" }
        exit 2
    }
    return $n
}

# form fields start at x=138; the settings search box starts at x=96
function FormEts($n) {
    return @($n | Select-String -Pattern '\tEditText\t' | ForEach-Object { $_.Line } |
             Where-Object { $_ -notmatch '\t\[96,' } | Sort-Object { Y0 $_ })
}

function FillEdit($text, $label, $idx = -1) {
    $n = AssertForm (Grab 'E2ECHK5560_nav_tmp')
    $ets = FormEts $n
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
    $n = Grab 'E2ECHK5560_nav_tmp'
    $closed = $n | Select-String -Pattern "^$want\t\[[0-9]+,1[0-9]{3}\]" | Select-Object -First 1
    if ($closed) {
        $p = B $closed.Line
        Write-Host "  auth toggle @ $($p[0]),$($p[1])"
        Tap $p[0] $p[1]
        Start-Sleep -Seconds 1
        $n = Grab 'E2ECHK5560_nav_tmp'
    }
    $opt = $n | Select-String -Pattern "^$want\t" | Select-Object -First 1
    if ($opt) { $p = B $opt.Line; Write-Host "  auth option '$want' @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] }
    else {
        Write-Host "  WARN auth option '$want' not found. menu shows:"
        ($n | Select-String -Pattern 'Password|Private key|Authenticat') |
            Select-Object -First 8 | ForEach-Object { Write-Host "    $_" }
        return $false
    }
    Start-Sleep -Seconds 1
    return $true
}

# swipe inside the dialog body only (never the pinned footer / keyboard)
function ScrollDown { A shell "input swipe 540 1500 540 1050 350" | Out-Null; Start-Sleep -Milliseconds 800 }

# ---------------- main ----------------
# -FormOnly: the Add SSH form is already on screen (nav done by hand), skip OpenForm.
if ($FormOnly) { Write-Host 'FormOnly - skipping OpenForm' }
else { OpenForm }
Shot "E2ECHK5560_${Tag}_10_form_open"

FillEdit 'ssh -p 2222 sshtest@127.0.0.1' 'host' 0 | Out-Null
ScrollDown
FillEdit $Name 'name' 1 | Out-Null
ScrollDown
PickAuth $Auth | Out-Null
ScrollDown
Shot "E2ECHK5560_${Tag}_11_form_auth"

if ($Auth -eq 'Private key') {
    FillEdit $KeyPath 'keypath' 2 | Out-Null
    ScrollDown
    $n = Grab 'E2ECHK5560_nav_tmp'
    $pp = $n | Select-String -Pattern 'Passphrase' | Select-Object -First 1
    if ($pp) { FillEdit $Passphrase 'passphrase' 3 | Out-Null }
    else { Write-Host '  (no Passphrase field present)' }
} else {
    FillEdit $Password 'password' | Out-Null
}

Shot "E2ECHK5560_${Tag}_12_before_submit"
$n = Grab 'E2ECHK5560_nav_tmp'
Write-Host '--- fields before submit ---'
($n | Select-String -Pattern '\tEditText\t') | ForEach-Object { Write-Host "  $($_.Line)" }
($n | Select-String -Pattern 'Add server|Cancel') | ForEach-Object { Write-Host "  $($_.Line)" }

if ($NoSubmit) { Write-Host 'NoSubmit - stop'; exit 0 }

$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if ($sub) { $p = B $sub.Line; Write-Host "SUBMIT @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] }
else { Write-Host 'WARN Add server not found' }
Start-Sleep -Seconds 4
Shot "E2ECHK5560_${Tag}_13_after_submit"
$n = Grab 'E2ECHK5560_nav_tmp'
Write-Host '--- state after submit ---'
($n | Select-String -Pattern 'Verify|SSH authentication|Password:|Cancel|Continue|Trust|Connected|Connecting|ailed|required|EditText|Passphrase|Key:') | ForEach-Object { Write-Host "  $($_.Line)" }
