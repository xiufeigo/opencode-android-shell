# E2ECHK5554_f14a.ps1 - F14a: unreadable private key file must surface the KEY-FILE
# sentence, not the target ("Enter a host or SSH connection command...") lie.
#
# FIX UNDER TEST (repo): SshConnectionModel.kt sshFailure() now maps
#   "privatekey" / "identity" / "filenotfound" / "no such file"  ->  SshErrorCode.KEY_FILE
# rendered from strings.xml `ssh_error_key_file`:
#   "Can't read the private key file. Check the path and read permission."
#
# SCENARIO: key path points at /sdcard/v95/id_ed25519 (owner u0_a218:media_rw 660;
# app uid u0_a230 -> Permission denied, sdcardfs ignores chmod). addIdentity() throws
# FileNotFoundException BEFORE any socket is opened.
#
# ASSERTIONS
#   A1 PASS: panel text contains  "read the private key file"
#   A2 FAIL-LEGACY: panel text contains  "Enter a host or SSH connection command"
#      (both must be reported; A2 true means the old lie is still alive)
#   A3 sshd MUST SEE ZERO sessions in the scenario window:
#      wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '<SINCE>' --no-pager | tail -15"
#      expected tail: "-- No entries --" (addIdentity throws pre-connect)
#   A4 row exists with a Connect affordance (entry was created), and is DELETED at
#      the end via the row's own menu (no BACK, no ESC).
#
# RUN ORDER: after Lead installs the F14 build -> E2ECHK5554_provision_key.ps1 -> this.
param(
    [string]$Dev = 'emulator-5554',
    [string]$Name = 'E2E5554K1',
    [string]$Target = 'ssh -p 2222 sshtest@127.0.0.1',
    [string]$BadKey = '/sdcard/v95/id_ed25519'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5554_run.ps1" -Dev $Dev
AssertDevice

$since = MarkNow
Write-Host "SSHD WINDOW MARK: $since"

# fixture pre-check: the bad key must exist AND be unreadable for the app uid
Write-Host '=== F14a fixture pre-check ==='
$ls = (A shell "ls -l /sdcard/v95/id_ed25519" | Out-String).Trim()
Write-Host "  $ls"
if ($ls -notmatch 'id_ed25519') { throw 'fixture missing: /sdcard/v95/id_ed25519' }
$denied = A shell "run-as $global:PKG cat /sdcard/v95/id_ed25519" 2>&1 | Out-String
Write-Host "  run-as cat -> $($denied.Trim())"
if ($denied -notmatch 'Permission denied') { Write-Host 'WARN: fixture is readable; pick another unreadable path (e.g. /data/local/tmp/nonesuch.key)' }

# build the entry
Write-Host '=== F14a build entry ==='
GotoServers
OpenAddForm
Shot 'E2ECHK5554_F14A_01_form_open'
Fill-SshForm -Target $Target -Name $Name -Auth PrivateKey -KeyPath $BadKey -Tag 'F14A'
SubmitForm
Start-Sleep -Seconds 5
Shot 'E2ECHK5554_F14A_02_after_submit'
$n = Grab
Write-Host '--- panel after submit (full node scan) ---'
($n | Select-String -Pattern $Name) | ForEach-Object { Write-Host "  $($_.Line)" }

# A1 / A2
$a1 = Has $n 'read the private key file'
$a2 = Has $n 'Enter a host or SSH connection command'
Write-Host "ASSERT A1 key-file sentence shown : $a1   (expect True)"
Write-Host "ASSERT A2 legacy target lie shown : $a2   (expect False)"
Shot 'E2ECHK5554_F14A_03_verdict'

# A3: zero sshd sessions in the window
Write-Host '=== A3 sshd window ==='
$jl = SshdLog $since 15
Write-Host $jl
$a3 = ($jl -join "`n") -match 'No entries'
Write-Host "ASSERT A3 sshd zero sessions      : $a3   (expect True)"

# A4: row exists
$a4 = Has $n ([regex]::Escape($Name))
Write-Host "ASSERT A4 row '$Name' exists      : $a4   (expect True)"

# cleanup: delete the entry via the row menu (Server actions is content-desc, not text)
Write-Host '=== F14a cleanup ==='
if ($a4) {
    $row = $n | Select-String -Pattern ([regex]::Escape($Name)) | Select-Object -First 1
    if ($row.Line -match '\[(\d+),(\d+)\]') {
        $ry = [int]$Matches[2]
        $menu = $null
        for ($scan = 0; $scan -lt 6 -and -not $menu; $scan++) {
            $m = Grab
            $menu = $m | Select-String -Pattern 'Server actions' | Select-Object -First 1
            if ($menu) { break }
            # scroll the LIST (never the dialog) to reveal the row menu
            A shell "input swipe 1000 1500 1000 1000 300" | Out-Null
            Start-Sleep -Milliseconds 700
        }
        if ($menu -and $menu.Line -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $mx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $my = [int]$Matches[2] + 150   # row menu sits ~300px under the title; +150 into the row body
            Tap $mx $my
            Start-Sleep -Milliseconds 900
            $n = Grab
            $del = $n | Select-String -Pattern '^Delete\t|Delete SSH server' | Select-Object -First 1
            if ($del) {
                $p = CenterOf $del.Line
                Tap $p[0] $p[1]
                Start-Sleep -Milliseconds 900
                # confirm strip may render below the fold: scroll the list, then confirm
                $n = Grab
                $ok = $n | Select-String -Pattern '^Delete\t' | Select-Object -First 1
                if ($ok) {
                    $p = CenterOf $ok.Line
                    Tap $p[0] $p[1]
                    Start-Sleep -Milliseconds 900
                }
            }
        }
    }
    Shot 'E2ECHK5554_F14A_90_cleanup'
    $n = Grab
    $gone = -not (Has $n ([regex]::Escape($Name)))
    Write-Host "CLEANUP entry removed: $gone (expect True)"
}

Write-Host '=== F14a VERDICT ==='
if ($a1 -and -not $a2 -and $a3) { Write-Host 'F14a PASS' }
elseif ($a2) { Write-Host 'F14a FAIL - legacy INPUT lie still shown' }
elseif (-not $a1) { Write-Host 'F14a FAIL/UNSEEN - key-file sentence not found; inspect F14A_02/03 evidence' }
else { Write-Host 'F14a PARTIAL - see A3 above' }
