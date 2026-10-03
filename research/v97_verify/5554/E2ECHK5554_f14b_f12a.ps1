# E2ECHK5554_f14b_f12a.ps1 - F14b + F12a (CORRECTED criteria, v96 Report section 3):
# a private-key entry with the passphrase LEFT EMPTY must trigger the passphrase
# prompt card; answering keypass123 must land Connected AND sshd must log
# "Accepted publickey for sshtest" (end-to-end proof that jsch offered the key).
#
# FIX UNDER TEST (repo): app/build.gradle.kts must carry net.i2p.crypto:eddsa (or an
# equivalent) so mwiede jsch 0.2.24 can SIGN with ed25519. F14b symptom when missing:
# connection opens, server falls back to keyboard-interactive, publickey NEVER offered.
#
# PRECONDITIONS (replay after every APK install):
#   .\E2ECHK5554_provision_key.ps1     (app-private copy of the key, md5-verified)
#
# CRITICAL PATH (v96 lesson): promptPassphrase card is ONLY reachable when the entry
# stores NO passphrase (SshConnection.kt: config.passphrase?.let { return true }).
# A stored wrong passphrase dies earlier in addIdentity. Hence: passphrase field EMPTY.
#
# TIGHT BLOCK: card-up -> field tap -> type keypass123 -> Continue runs with NO dumps
# in between (kbdint session expires otherwise). First run on a new build:
#   1) run this script with -RehearseOnly to LEARN FieldX/Y ContX/Y (card is cancelled)
#   2) re-run with the learned numbers for the real verdict.
#
# ASSERTIONS
#   A1 prompt card appeared  : node table shows "SSH authentication" (+ Passphrase)
#   A2 row reaches Connected : outcome sample shows Connected
#   A3 sshd logs Accepted publickey for sshtest in the window
#   A4 negative check: NO "Could not establish" in the final row state
param(
    [string]$Dev = 'emulator-5554',
    [string]$Name = 'E2E5554K2',
    [string]$Target = 'ssh -p 2222 sshtest@127.0.0.1',
    [string]$KeyPath = '/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519',
    [string]$Passphrase = 'keypass123',
    [int]$FieldX = 540,    # TODO: learn via -RehearseOnly on the first run
    [int]$FieldY = 1641,   # TODO
    [int]$ContX = 866,     # TODO
    [int]$ContY = 1347,    # TODO
    [switch]$RehearseOnly
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
AssertDevice

# pre-flight: the key must be in place and app-readable
$md5 = ((A shell "run-as $global:PKG md5sum files/id_ed25519" | Out-String) -split '\s+')[0].Trim()
$repo = (Get-FileHash $global:KEY_REPO -Algorithm MD5).Hash.ToLower()
Write-Host "KEY pre-flight device=$md5 repo=$repo"
if ($md5 -ne $repo) { throw 'app-private key missing/stale - run E2ECHK5554_provision_key.ps1 first' }

# build the entry (passphrase intentionally EMPTY)
Write-Host '=== F14b+F12a build entry (passphrase EMPTY) ==='
GotoServers
OpenAddForm
Shot 'E2ECHK5554_F12A_01_form_open'
Fill-SshForm -Target $Target -Name $Name -Auth PrivateKey -KeyPath $KeyPath -Tag 'F12A'
SubmitForm
Start-Sleep -Seconds 6
Shot 'E2ECHK5554_F12A_02_after_submit'
$n = Grab
Write-Host '--- panel after submit ---'
($n | Select-String -Pattern "SSH authentication|Passphrase|Continue|Cancel|Connected|Connection failed|Could not|$Name") |
    Select-Object -First 20 | ForEach-Object { Write-Host "  $($_.Line)" }

# A1: did the passphrase card come up already (submit itself can trigger it)?
$a1 = (Has $n 'SSH authentication')
Write-Host "ASSERT A1 card shown after submit : $a1"
if ($RehearseOnly) {
    Write-Host 'REHEARSE ONLY - learning coordinates, then cancelling'
    if ($a1) {
        # resolve Reconnect is not needed; the card is already up: rehearse layout only
        Rehearse-Card -ReconnectX 540 -ReconnectY 1500 -Tag 'F12AR'
    } else {
        $rc = $n | Select-String -Pattern '^Reconnect\t' | Select-Object -First 1
        if (-not $rc) { throw 'no Reconnect affordance (row not in connectable state)' }
        $p = CenterOf $rc.Line
        Rehearse-Card -ReconnectX $p[0] -ReconnectY $p[1] -Tag 'F12AR'
    }
    Write-Host 'NOW re-run without -RehearseOnly, passing the LEARNED FieldX/Y ContX/Y'
    return
}

$since = MarkNow
if (-not $a1) {
    # card not up: drive it via the row Reconnect, coordinates from the dump
    $rc = $n | Select-String -Pattern '^Reconnect\t' | Select-Object -First 1
    if (-not $rc) { Shot 'E2ECHK5554_F12A_09_no_reconnect'; throw 'no Reconnect affordance' }
    $p = CenterOf $rc.Line
    Answer-Card -ReconnectX $p[0] -ReconnectY $p[1] -Secret $Passphrase -FieldX $FieldX -FieldY $FieldY -ContX $ContX -ContY $ContY -Tag 'F12A'
} else {
    # card already up from submit: answer in a tight block from here
    ResetFaillock | Out-Null
    A shell "input tap $FieldX $FieldY" | Out-Null
    Start-Sleep -Milliseconds 800
    $words = @($Passphrase -split ' ' | Where-Object { $_ -ne '' })
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
    Shot 'E2ECHK5554_F12A_40_result'
}

# A2/A4: row state
$n = Grab
Write-Host '--- final row state ---'
($n | Select-String -Pattern "$Name|Connected|Could not|Reconnect|Disconnect|left running") |
    Select-Object -First 16 | ForEach-Object { Write-Host "  $($_.Line)" }
$a2 = Has $n 'Connected'
$a4 = -not (Has $n 'Could not establish')
Write-Host "ASSERT A2 row Connected           : $a2   (expect True)"
Write-Host "ASSERT A4 no 'Could not establish': $a4   (expect True)"

# A3: sshd must log Accepted publickey
Write-Host '=== A3 sshd window (Accepted publickey required) ==='
$jl = SshdAcceptedPub $since
Write-Host $jl
$a3 = ($jl -join "`n") -match 'Accepted publickey'
Write-Host "ASSERT A3 Accepted publickey      : $a3   (expect True)"

Write-Host '=== F14b+F12a VERDICT ==='
if ($a2 -and $a3) { Write-Host 'F14b+F12a PASS - ed25519 offered and accepted end-to-end' }
elseif ($a2 -and -not $a3) { Write-Host 'PARTIAL - app says Connected but no publickey line; check fallback (F14b suspicion)' }
else { Write-Host 'FAIL - inspect F12A_* evidence and sshd window above' }
