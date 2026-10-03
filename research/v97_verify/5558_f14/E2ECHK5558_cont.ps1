# Continue F14b from the open form: reveal keypath, type it, submit, then card + journalctl.
param([string]$Dev = 'emulator-5558')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5558_run.ps1" -Dev $Dev
$KeyPath = '/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'

# reveal the keypath field: scroll the form down a couple of fast swipes
for ($i = 0; $i -lt 4; $i++) {
    $n = Grab
    $pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    $kps = 0
    if ($pk) {
        $pkr = RectOf $pk.Line
        $kps = @($n | Select-String -Pattern 'EditText\t' | ForEach-Object { $_.Line } |
            Where-Object { $r = RectOf $_; $r -and $r[1] -gt $pkr[3] -and $r[1] -lt ($pkr[3] + 300) }).Count
    }
    Write-Host "  reveal pass ${i}: fields below 'Private key' = $kps"
    if ($kps -ge 1) { break }
    FormSwipe $n -1
}
$n = Grab
Write-Host '--- auth view nodes ---'
($n | Select-String -Pattern 'EditText|Private key|Add server|Cancel') | ForEach-Object { Write-Host "  $_" }

# tap the keypath field (the auth EditText below the 'Private key' label)
$pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
$target = $null
if ($pk) {
    $pkr = RectOf $pk.Line
    $cands = @($n | Select-String -Pattern 'EditText\t' | ForEach-Object { $_.Line } | Where-Object {
        $r = RectOf $_
        $r -and $r[1] -gt $pkr[3] -and $r[1] -lt ($pkr[3] + 320) -and ($r[3] - $r[1]) -ge 30
    } | ForEach-Object { $_ })
    if ($cands.Count -gt 0) { $target = $cands[0] }
}
if (-not $target) { $target = (@($n | Select-String -Pattern 'EditText\t' | ForEach-Object { $_.Line }) | Select-Object -Last 1) }
if (-not $target) { Shot 'E2ECHK5558_F12A_keypath_nofind'; throw 'keypath field not found' }
$p = CenterOf $target
Write-Host "TAP keypath @ $($p[0]),$($p[1])  <<$target>>"
Tap $p[0] $p[1] 900
TypeText $KeyPath
Start-Sleep -Milliseconds 700
CollapseIme
Shot 'E2ECHK5558_F12A_04_keypath_typed'
$n = Grab
Write-Host '--- form before submit ---'
($n | Select-String -Pattern 'EditText|Add server') | ForEach-Object { Write-Host "  $_" }
Write-Host "MARK2 $(MarkNow)"
SubmitForm
Start-Sleep -Seconds 8
Shot 'E2ECHK5558_F12A_02_after_submit'
$n = Grab
Write-Host '--- panel after submit ---'
($n | Select-String -Pattern "SSH authentication|Passphrase|Continue|Cancel|Connected|Could not|E2ECHK5558_f14b") |
    Select-Object -First 15 | ForEach-Object { Write-Host "  $($_.Line)" }

# sshd verdict (the core F14b signal)
Write-Host '=== sshd verdict (window 10min) ==='
SshdAcceptedPub '-10min' | ForEach-Object { Write-Host "  $_" }
Write-Host 'DONE cont'
