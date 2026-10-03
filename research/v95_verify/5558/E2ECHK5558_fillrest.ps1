# E2ECHK5558_fillrest.ps1 — fill name + credential on an ALREADY-OPEN form whose host is set
param(
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Password = '',
    [string]$KeyPath = '',
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
function Fields($n) {
    @($n | Select-String -Pattern '\tEditText\t' | ForEach-Object { $_.Line } |
        Where-Object { $_ -match '\t\[138,' } | Sort-Object { [int]($_ -replace '^.*\[138,(\d+)\].*$', '$1') })
}
function FieldH($line) { if ($line -match '\[138,(\d+)\]\[(\d+),(\d+)\]') { return ([int]$Matches[3] - [int]$Matches[1]) }; return 0 }
function FormSwipe($n) {
    $ft = $n | Select-String -Pattern '^Add server\t|^Cancel\t' | Select-Object -First 1
    $fy = 1780
    if ($ft -and $ft.Line -match '\[(\d+),(\d+)\]') { $fy = [int]$Matches[2] }
    $y1 = $fy - 90; $y2 = $y1 - 420; if ($y2 -lt 700) { $y2 = 700 }
    Write-Host "  swipe 540,$y1 -> 540,$y2"
    & $ADB -s emulator-5558 shell "input swipe 540 $y1 540 $y2 350" 2>&1 | Out-Null
    Start-Sleep -Milliseconds 900
}
function TapLine($line, $what) {
    $p = B $line
    if (-not $p) { Write-Host "  FAIL bounds $what"; return $false }
    Write-Host "  tap $what @ $($p[0]),$($p[1])   <<$(($line -split "`t")[0])>>"
    Tap $p[0] $p[1]; Start-Sleep -Milliseconds 900
    return $true
}
function ScrollToIdx($idx, $what) {
    for ($i = 0; $i -lt 6; $i++) {
        $n = Grab; $f = Fields $n
        if ($f.Count -gt $idx -and (FieldH $f[$idx]) -ge 50) { return ,@($f[$idx], $n) }
        FormSwipe $n
    }
    Write-Host "  FAIL '$what' idx$idx"; return $null
}
function ScrollToPw($what) {
    for ($i = 0; $i -lt 6; $i++) {
        $n = Grab; $f = Fields $n
        $hit = $f | Where-Object { $_ -match 'pw=true' -and (FieldH $_) -ge 50 } | Select-Object -First 1
        if ($hit) { return ,@($hit, $n) }
        FormSwipe $n
    }
    Write-Host "  FAIL '$what' pw"; return $null
}

$n = Grab
$isForm = ($n | Select-String -Pattern '^Host or SSH command\t' | Select-Object -First 1)
if (-not $isForm) { Write-Host 'ABORT: form not open'; exit 2 }

# name = form field index 1 (index 0 is the host, already filled)
$res = ScrollToIdx 1 'name'
if (-not $res) { exit 2 }
TapLine $res[0] 'name' | Out-Null
TypeText $Name
Start-Sleep -Milliseconds 600

if ($PickPrivateKey) {
    $n = Grab
    $tog = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1
    if ($tog) { TapLine $tog.Line 'auth-toggle' | Out-Null; Start-Sleep -Seconds 1 }
    $n = Grab
    $opt = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if ($opt) { TapLine $opt.Line 'auth=PrivateKey' | Out-Null; Start-Sleep -Seconds 1 }
    else { Write-Host 'WARN Private key option missing'; ($n | Select-String -Pattern 'Password|Private key') | Select-Object -First 6 | ForEach-Object { Write-Host "   $($_.Line)" } }
}

$res = ScrollToPw 'credential'
if (-not $res) { exit 2 }
$n = $res[1]
TapLine $res[0] 'credential' | Out-Null
if ($KeyPath -ne '') { TypeText $KeyPath }
elseif ($Password -ne '') { Write-Host '  (password intentionally left for the prompt card)' }

Shot "E2ECHK5558_${Tag}_20_form_typed"
$n = Grab
Write-Host '--- form state (keyboard up) ---'
Fields $n | ForEach-Object { Write-Host "  FLD $_" }
($n | Select-String -Pattern '^Add server\t|^Cancel\t|^Password\t|^Private key\t|Passphrase|key|Key') | ForEach-Object { Write-Host "  $_" }
Shot "E2ECHK5558_${Tag}_21_form_ready"
