# E2ECHK5558_s2_space.ps1 - v75: the text actually SENT keeps its whitespace.
#
# Background (HANDOFF.md:65): `submit()` now sends the ORIGINAL text (`outgoing`),
# `trim()` only feeds the emptiness gate.  The previous round had no APK on the
# device, so the claim "send `hello ` (trailing space) -> the stored text keeps the
# trailing space" was never verified on hardware.
#
# TWO TURNS, ONE SESSION:
#   A) "hello" + KEYCODE_SPACE(62) + "world"  -> stored text must be "hello world"
#      (a MIDDLE space - proves nothing was collapsed in transit)
#   B) "hello" + KEYCODE_SPACE(62)            -> stored text must be "hello "
#      (a TRAILING space - the case the fix is about)
#
# TEST DISCIPLINE (this is the part that matters): a LITERAL trailing space inside
# `adb shell input text` is dropped by the platform before the app ever sees it
# (v75 measured it in the system Settings EditText too), and `%s` is banned on this
# image.  So the space is ALWAYS injected as a real KEYCODE_SPACE key event.
param(
    [string]$Dev = 'emulator-5558'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\E2ECHK5558_run.ps1" -Dev $Dev

$before = @((SessionList) | ForEach-Object { $_.id })

function ComposerText {
    $n = Nodes $args[0]
    $et = $n | Select-String -Pattern 'android\.widget\.EditText' | Select-Object -First 1
    if (-not $et) { return $null }
    return @($et.Line, $n)
}
function TapSend($n) {
    $s = $n | Select-String -Pattern '^Send\t' | Select-Object -First 1
    if (-not $s) { throw 'Send button not found' }
    $p = CenterOf $s.Line
    Write-Host "$(Stamp) TAP Send @ $($p[0]),$($p[1])"
    A shell "input tap $($p[0]) $($p[1])" | Out-Null
    Start-Sleep -Milliseconds 1400
}

# ---------------- TURN A: middle space ----------------
Write-Host '########## TURN A: hello <SPACE> world ##########'
TypeWord 'hello' 900
Space 600
TypeWord 'world' 900
Start-Sleep -Milliseconds 800
$ct = ComposerText 'E2ECHK5558_S2_10A_typed'
if (-not $ct) { throw 'composer not found' }
Write-Host "COMPOSER A: $($ct[0])"
Shot 'E2ECHK5558_S2_10A_typed' | Out-Null
TapSend $ct[1]

# ---------------- TURN B: trailing space ----------------
Write-Host '########## TURN B: hello <SPACE>  (trailing) ##########'
Start-Sleep -Seconds 2
# the composer is empty again after the send; re-focus it from a fresh dump
$pre = Nodes 'E2ECHK5558_S2_20B_pre'
$et = $pre | Select-String -Pattern 'android\.widget\.EditText' | Select-Object -First 1
if ($et) {
    $p = CenterOf $et.Line
    Write-Host "$(Stamp) focus composer @ $($p[0]),$($p[1])"
    A shell "input tap $($p[0]) $($p[1])" | Out-Null
    Start-Sleep -Milliseconds 900
}
TypeWord 'hello' 900
Space 700
Start-Sleep -Milliseconds 900
$ct2 = ComposerText 'E2ECHK5558_S2_20B_typed'
if (-not $ct2) { throw 'composer not found for turn B' }
Write-Host "COMPOSER B: $($ct2[0])"
Shot 'E2ECHK5558_S2_20B_typed' | Out-Null
TapSend $ct2[1]

Start-Sleep -Seconds 6
Shot 'E2ECHK5558_S2_30_timeline' | Out-Null
Nodes 'E2ECHK5558_S2_31_timeline' | Tee-Object -FilePath (Join-Path $global:DIR 'S2_timeline_nodes.txt') | Select-String -Pattern 'hello|world'

# ---------------- server read-back ----------------
$after = SessionList
$new = @($after | Where-Object { $before -notcontains $_.id })
if ($new.Count -eq 0) { Write-Host '!! no new session on the server'; return }
$sid = $new[0].id
Write-Host "NEW SESSION id=$sid title='$($new[0].title)' dir=$($new[0].location.directory)"
$msgs = SessionMessages $sid 'S2_messages.json'
Write-Host '--- user rows, byte-exact ---'
& $global:PY (Join-Path $global:DIR 'E2ECHK5558_spaces.py') (Join-Path $global:DIR 'S2_messages.json')
