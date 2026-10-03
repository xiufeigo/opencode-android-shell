# F14b + F12a END-TO-END on emulator-5558 (RECALIBRATED for 1344 wide).
# A private-key entry with the passphrase LEFT EMPTY must raise the passphrase card;
# answering keypass123 must reach Connected AND sshd must log "Accepted publickey"
# (end-to-end proof the ed25519 route works). If sshd logs "Accepted
# keyboard-interactive" instead -> F14b FAIL (bcprov/ed25519 routing not effective).
param(
    [string]$Dev = 'emulator-5558',
    [string]$Name = 'E2ECHK5558_f14b',
    [string]$Target = 'ssh -p 2222 sshtest@127.0.0.1',
    [string]$KeyPath = '/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519',
    [string]$Passphrase = 'keypass123',
    [switch]$SkipProvision
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5558_run.ps1" -Dev $Dev
AssertDevice
if (-not $SkipProvision) { Write-Host '=== provision key ==='; EnsureKey }
$md5 = ((A shell "run-as $global:PKG md5sum files/id_ed25519" | Out-String) -split '\s+')[0].Trim()
$repo = (Get-FileHash $global:KEY_REPO -Algorithm MD5).Hash.ToLower()
Write-Host "KEY pre-flight device=$md5 repo=$repo"
if ($md5 -ne $repo) { throw 'app-private key missing/stale' }
$since = MarkNow
Write-Host "MARK $since"

Write-Host '=== clean launch ==='
LaunchApp | Out-Null
Start-Sleep -Seconds 2
ReachForm
Shot 'E2ECHK5558_F12A_01_form_open'
Fill-SshForm -Target $Target -Name $Name -Auth PrivateKey -KeyPath $KeyPath -Tag 'F12A'
SubmitForm
Start-Sleep -Seconds 6
Shot 'E2ECHK5558_F12A_02_after_submit'
$n = Grab
Write-Host '--- panel after submit ---'
($n | Select-String -Pattern "SSH authentication|Passphrase|Continue|Cancel|Connected|Connection failed|Could not|$Name") |
    Select-Object -First 20 | ForEach-Object { Write-Host "  $($_.Line)" }
$a1 = ((Has $n 'SSH authentication') -or (Has $n 'Passphrase'))
Write-Host "ASSERT A1 card-shown=$a1"

# Learn card coords (IME up) then cancel. Rehearsal dumps may expire the challenge.
$FieldX = 0; $FieldY = 0; $ContX = 0; $ContY = 0
$card = $n | Select-String -Pattern 'SSH authentication' | Select-Object -First 1
if ($card) {
    $fld = $n | Select-String -Pattern 'EditText\t' | Select-Object -First 1
    $con = $n | Select-String -Pattern '^Continue\t' | Select-Object -First 1
    if ($fld -and $con) {
        $pf = CenterOf $fld.Line
        Write-Host "  tap card field @ $($pf[0]),$($pf[1])"
        Tap $pf[0] $pf[1] 1000
        $n2 = Grab
        $f2 = $n2 | Select-String -Pattern 'EditText\t' | Select-Object -First 1
        $c2 = $n2 | Select-String -Pattern '^Continue\t' | Select-Object -First 1
        if ($f2) { $p = CenterOf $f2.Line; $FieldX = $p[0]; $FieldY = $p[1]; Write-Host "LEARNED FieldX=$FieldX FieldY=$FieldY" }
        if ($c2) { $p = CenterOf $c2.Line; $ContX = $p[0]; $ContY = $p[1]; Write-Host "LEARNED ContX=$ContX ContY=$ContY" }
        Shot 'E2ECHK5558_F12A_03_card_imeup'
        $can = $n2 | Select-String -Pattern '^Cancel\t' | Select-Object -First 1
        if ($can) { $p = CenterOf $can.Line; Write-Host "  card cancel @ $($p[0]),$($p[1])"; Tap $p[0] $p[1] }
    } else { Write-Host 'card layout not recognised' }
}
ResetFaillock | Out-Null
Write-Host "REHEARSE DONE (A1=$a1). Learned: FX=$FieldX FY=$FieldY CX=$ContX CY=$ContY"

# --- reconnect and answer the card with the learned coords ---
$n = Grab
$rec = $n | Select-String -Pattern 'Reconnect' | Select-Object -First 1
if ($rec) {
    $p = CenterOf $rec.Line
    Write-Host "RECONNECT btn @ $($p[0]),$($p[1])"
    Write-Host '=== answer card ==='
    Answer-Card -ReconnectX $p[0] -ReconnectY $p[1] -Secret $Passphrase -FieldX $FieldX -FieldY $FieldY -ContX $ContX -ContY $ContY -Tag 'F12A'
} else {
    Write-Host 'no Reconnect button found; panel nodes:'
    ($n | Select-String -Pattern "$Name|Connected|Reconnect|Server actions") | Select-Object -First 10 | ForEach-Object { Write-Host "  $($_.Line)" }
}

Write-Host '=== sshd verdict ==='
SshdAcceptedPub '-10min' | ForEach-Object { Write-Host "  $_" }
Write-Host 'DONE f14b'
