# d3g_lf3.ps1 - form open. Scroll the form card the OTHER way (content up) to bring the
# Authentication chips / password field into the clipped card, then fill + submit.
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
function TypeS($t) { TypeText $t 8 550 }
AssertDevice; Anr

# scroll content UP inside the card: swipe from low to high
for ($i = 0; $i -lt 4; $i++) {
    Anr
    A shell "input swipe 540 1330 540 1050 350" | Out-Null
    Start-Sleep -Milliseconds 800
    $n = Snap "s_$i"
    $hit = $n | Select-String -Pattern 'Authentication|^Password\t|^Private key\t'
    Log "scroll $i : auth-ish nodes = $(@($hit).Count)"
    if ($hit) { $hit | ForEach-Object { Log "   $($_.Line)" } }
    if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) { Log 'FORM CLOSED BY SCROLL'; Shot 'D3G_LF3_closed'; return }
}
$n = Snap 's_final'
Log '--- final form view ---'
$n | Select-String -Pattern 'TextView|EditText' | ForEach-Object { Log "  $($_.Line)" }
Shot 'D3G_LF3_01'
Log 'LF3 DONE'
