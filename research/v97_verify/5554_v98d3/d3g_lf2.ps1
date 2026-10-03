# d3g_lf2.ps1 - form is OPEN and focused. Fix host/name, reveal password field by
# scrolling INSIDE the form card, submit, then connect and delete.
# NOTE: `input text` drops '-' (v96 lesson generalised): 'ssh -p 2222 ...' landed as
# 'ssh2222 ...'. Switch to the dash-free, proven form sshtest@127.0.0.1:2222.
param([string]$Dev = 'emulator-5554')
$ErrorActionPreference = 'Continue'
$HELP = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554'
. "$HELP\E2ECHK5554_run.ps1" -Dev $Dev
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3'
$LOG = Join-Path $global:DIR 'd3g_run.log'
function Log($m) { $s = "[{0:HH:mm:ss}] {1}" -f (Get-Date), $m; Write-Host $s; Add-Content -Path $LOG -Value $s }
$ANRN = 0
function Anr { for ($i=0;$i -lt 3;$i++){ $f=(A shell "dumpsys window | grep mCurrentFocus" | Out-String); if ($f -notmatch 'Not Responding'){return}; $script:ANRN++; A shell "input tap 540 1406" | Out-Null; Start-Sleep -Milliseconds 700; Log "ANR #$($script:ANRN) dismissed" } }
function Snap($t) { Anr; return (Nodes $t) }
function Ctr($l) { if ($l) { return (CenterOf $l) } return $null }
function Flds($n) { @($n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match '\t\[138,' }) }
function TypeS($t) { TypeText $t 8 600 }
AssertDevice; Anr
ResetFaillock | Out-Null

# ---- dump whole form so we can see labels and the card bounds ----
$n = Snap 'lf2_0'
Log '--- full form ---'
$n | Select-String -Pattern 'TextView|EditText' | ForEach-Object { Log "  $($_.Line)" }

# ---- scroll INSIDE the form card to reveal the auth chips / password field ----
# card top = min y of the x=138 EditTexts, minus ~120; swipe up from inside it.
$fs = Flds $n
if ($fs.Count -ge 2) {
    $r1 = RectOf $fs[0].Line
    $y1 = $r1[1] - 60
    $y2 = $r1[1] + 300
    Log "form-body swipe inside card: $y1 -> $y2 (x=540)"
    Anr; A shell "input swipe 540 $y1 540 $y2 400" | Out-Null; Start-Sleep -Milliseconds 900
}
$n = Snap 'lf2_1'
Log '--- after in-card scroll ---'
$n | Select-String -Pattern 'TextView|EditText' | ForEach-Object { Log "  $($_.Line)" }
Shot 'D3G_LF2_01_scrolled'
Log 'LF2 DONE'
