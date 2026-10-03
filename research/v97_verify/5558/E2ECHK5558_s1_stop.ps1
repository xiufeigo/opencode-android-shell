# E2ECHK5558_s1_stop.ps1 - v74: Stop landing BEFORE the provider produced any output.
#
# ASSERTION (from research/HANDOFF.md:62): when Stop lands while the provider has
# emitted nothing, the SERVER writes no assistant row - only a user row plus
# `idle{outcome:"interrupted"}` three seconds later.  Before the v74 fix the shell
# drew nothing at all for such a turn.  The fix (ChatStore.TurnMarker /
# applyTurnMarker) inserts a PARTLESS marker row that carries the divider.
# So on device the timeline must show:
#   (a) the USER bubble (the v62 handoff local row), and
#   (b) an "Interrupted" divider/meta line - NOT a bare, empty assistant turn.
#
# DISCIPLINE: no BACK, no ESC; the Stop tap is resolved from a FRESH dump; the
# prompt is typed in <=8 char segments with real KEYCODE_SPACE between words.
param(
    [string]$Dev = 'emulator-5558',
    [string]$Prompt = 'E2ECHK5558_STOP explain in depth how a transistor works and why it replaced the vacuum tube, be very thorough and consider every angle'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5558_run.ps1" -Dev $Dev

$before = @((SessionList) | ForEach-Object { $_.id })
Write-Host "sessions before: $($before.Count)"

# ---- 1. type the prompt into the focused composer (skipped if already there) ----
$probe = Nodes 'E2ECHK5558_S1_19_probe'
if ($probe | Select-String -Pattern 'E2ECHK5558_STOP explain' -Quiet) {
    Write-Host '=== prompt already in the composer; skipping typing ==='
} else {
    Write-Host '=== typing prompt ==='
    TypePhrase $Prompt
    Start-Sleep -Milliseconds 800
}
$typed = Nodes 'E2ECHK5558_S1_20_typed'
$et = $typed | Select-String -Pattern 'android\.widget\.EditText' | Select-Object -First 1
if (-not $et) { throw 'composer EditText not found' }
Write-Host "COMPOSER: $($et.Line)"
Shot 'E2ECHK5558_S1_20_typed' | Out-Null

# ---- 2. send (Send button resolved from the same dump) ----
$send = $typed | Select-String -Pattern '^Send\t' | Select-Object -First 1
if (-not $send) { throw 'Send button not found' }
$sp = CenterOf $send.Line
$t0 = Ms
Write-Host "$(Stamp) TAP Send @ $($sp[0]),$($sp[1])"
A shell "input tap $($sp[0]) $($sp[1])" | Out-Null

# ---- 3. Stop as early as possible, resolved from a fresh dump ----
# The send tap already put the app in the running state; poll for the Stop
# affordance and tap it the moment it is in a dump.  The dump latency IS the
# pre-stream window this test lives in - it is reported, not hidden.
$stopLine = $null
$stopAt = $null
for ($i = 0; $i -lt 5; $i++) {
    Start-Sleep -Milliseconds 200
    $n = Nodes "E2ECHK5558_S1_21_running_$i"
    $s = $n | Select-String -Pattern '^Stop\b' | Select-Object -First 1
    if ($s) { $stopLine = $s.Line; break }
    Write-Host "  (no Stop in dump $i at $(Stamp))"
}
if ($stopLine) {
    $pp = CenterOf $stopLine
    $stopAt = Ms
    Write-Host "$(Stamp) TAP Stop @ $($pp[0]),$($pp[1])   [Stop tapped $((Ms) - $t0)ms after Send]"
    A shell "input tap $($pp[0]) $($pp[1])" | Out-Null
} else {
    Write-Host "!! no Stop affordance found in 5 dumps - cannot run the pre-stream Stop"
}
$stoppedAt = Ms

# ---- 4. sample the timeline ----
# v74 measured: the `idle{outcome:"interrupted"}` marker lands ~3s after the stop.
$rows = @()
for ($k = 1; $k -le 8; $k++) {
    Start-Sleep -Seconds 2
    $n = Nodes "E2ECHK5558_S1_3${k}_sample"
    $hasInterrupted = [bool]($n | Select-String -Pattern 'Interrupted' -Quiet)
    $hasPrompt = [bool]($n | Select-String -Pattern 'E2ECHK5558_STOP' -Quiet)
    $rows += [pscustomobject]@{
        sample = $k
        atMs = (Ms) - $stopAt
        interruptedDivider = $hasInterrupted
        userBubble = $hasPrompt
    }
    Write-Host ("  sample {0} (+{1}ms): Interrupted={2} userBubble={3}" -f $k, ((Ms) - $stopAt), $hasInterrupted, $hasPrompt)
    if ($hasInterrupted -and $hasPrompt) { break }
}
$rows | Format-Table -AutoSize | Out-String -Width 160 | Write-Host
$rows | Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $global:DIR 'E2ECHK5558_S1_samples.csv')
Shot 'E2ECHK5558_S1_40_after_stop' | Out-Null
Nodes 'E2ECHK5558_S1_41_after_stop' | Tee-Object -FilePath (Join-Path $global:DIR 'S1_final_nodes.txt')

Write-Host "TIMING: Send=$t0ms  StopTapped=$stopAt  StopSettled=$stoppedAt  elapsed=$((Ms))ms"

# ---- 5. server-side read-back ----
$after = SessionList
$new = @($after | Where-Object { $before -notcontains $_.id })
if ($new.Count -eq 0) { Write-Host '!! no new session on the server'; return }
$sid = $new[0].id
Write-Host "NEW SESSION id=$sid title='$($new[0].title)' model=$($new[0].model.id) dir=$($new[0].location.directory)"
$new[0] | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 (Join-Path $global:DIR 'S1_session.json')
$msgs = SessionMessages $sid 'S1_messages.json'
$j = ($msgs | ConvertFrom-Json)
Write-Host "--- server messages (role / type / first 90 chars) ---"
foreach ($m in $j) {
    $t = ''
    foreach ($p in $m.parts) { if ($p.text) { $t = $p.text }; if ($p.type -eq 'idle') { $t = "[idle outcome=$($p.outcome)]" } }
    if ($t.Length -gt 90) { $t = $t.Substring(0, 90) + '...' }
    Write-Host ("  id={0} role={1} type={2} outcome={3} text='{4}'" -f $m.id, $m.role, $m.type, $m.outcome, $t)
}
