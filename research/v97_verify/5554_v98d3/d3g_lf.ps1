# d3g_lf.ps1 - null-safe last-ditch fill. Servers panel is already open.
param([string]$Dev = 'emulator-5554')
$ErrorActionPreference = 'Continue'
$HELP = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554'
. "$HELP\E2ECHK5554_run.ps1" -Dev $Dev
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3'
$LOG = Join-Path $global:DIR 'd3g_run.log'
function Log($m) { $s = "[{0:HH:mm:ss}] {1}" -f (Get-Date), $m; Write-Host $s; Add-Content -Path $LOG -Value $s }
$ANRN = 0
function Anr {
    for ($i = 0; $i -lt 3; $i++) {
        $f = (A shell "dumpsys window | grep mCurrentFocus" | Out-String)
        if ($f -notmatch 'Not Responding') { return }
        $script:ANRN++; A shell "input tap 540 1406" | Out-Null; Start-Sleep -Milliseconds 700
        Log "ANR #$($script:ANRN) dismissed"
    }
}
function Snap($t) { Anr; return (Nodes $t) }
function Ctr($line) { if ($line) { return (CenterOf $line) } return $null }
function Fld($n, $y0, $y1) {
    foreach ($f in ($n | Select-String -Pattern 'EditText\t')) {
        $r = RectOf $f.Line
        if ($r -and $r[0] -eq 138 -and $r[1] -ge $y0 -and $r[1] -le $y1) { return $f.Line }
    }
    return $null
}
function TapAt($p, $w) { if (-not $p) { Log "  no coords for $w"; return $false }; Log "tap $w @ $($p[0]),$($p[1])"; A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Milliseconds 800; return $true }
function TypeS($t) { TypeText $t 8 600 }
AssertDevice; Anr

$n = Snap 'lf0'
Log '--- panel nodes ---'
$n | Select-String -Pattern 'Add SSH server|Servers\t|Server name' | ForEach-Object { Log "  $($_.Line)" }
if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) {
    $add = $n | Select-String -Pattern 'Add SSH server' | Select-Object -First 1
    if (TapAt (Ctr $add.Line) 'Add SSH server') {
        Start-Sleep -Seconds 2
        $n = Snap 'lf1'
    }
}
if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) { Shot 'D3G_LF_00'; Log 'FORM NOT OPEN'; return }
Log 'form open'
$lab = $n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1
if (-not $lab) { $lab = $n | Select-String -Pattern 'Server name' | Select-Object -First 1 }
$lr = RectOf $lab.Line
$nf = Fld $n ($lr[3]) ($lr[3] + 250)
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
if (-not $nf -or -not $hf) { Shot 'D3G_LF_01'; Log 'FIELDS MISSING'; return }
TapAt (Ctr $nf) 'name' | Out-Null; Anr; TypeS 'E2ECHK5554d3g-key'; Start-Sleep -Milliseconds 200; CollapseIme
$n = Snap 'lf2'
$lr = RectOf (($n | Select-String -Pattern 'Server name' | Select-Object -First 1).Line)
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
TapAt (Ctr $hf) 'host' | Out-Null; Anr; TypeS 'ssh -p 2222 sshtest@127.0.0.1'; Start-Sleep -Milliseconds 200; CollapseIme
Log 'name+host typed'
Shot 'D3G_LF_02_named'
$n = Snap 'lf3'
$n | Select-String -Pattern 'EditText\t|^Password\t|^Private key\t|Add server' | ForEach-Object { Log "  $($_.Line)" }
$chip = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if (-not $chip) { $chip = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1 }
if ($chip) {
    TapAt (Ctr $chip.Line) "chip:$((($chip.Line -split "`t")[0]))" | Out-Null
    Start-Sleep -Seconds 1
    $n = Snap 'lf4'
    $pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if ($pk -and $pk.Line -ne $chip.Line) { TapAt (Ctr $pk.Line) 'PrivateKey' | Out-Null; Start-Sleep -Seconds 1 }
    Shot 'D3G_LF_03_chip'
} else { Shot 'D3G_LF_04_nochip'; Log 'NO CHIP VISIBLE' }
$n = Snap 'lf5'
$n | Select-String -Pattern 'EditText\t|^Add server\t|^Private key\t' | ForEach-Object { Log "  FLD $($_.Line)" }
Log 'LF DONE'
