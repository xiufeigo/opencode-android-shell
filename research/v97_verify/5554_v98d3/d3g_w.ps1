# d3g_w.ps1 - final consolidated attempt. ANR-guarded, NO form-dismissing swipes:
# the auth chips are revealed by collapsing the IME (keyevent 61 TAB), which the
# v97 helper documents as restoring the full card. A swipe inside this nested form
# reads as drag-to-dismiss and closes it (that is what killed the last two runs).
param([string]$Dev = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$HELP = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554'
. "$HELP\E2ECHK5554_run.ps1" -Dev $Dev
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3'
$LOG = Join-Path $global:DIR 'd3g_run.log'
function Log($m) { $s = "[{0:HH:mm:ss}] {1}" -f (Get-Date), $m; Write-Host $s; Add-Content -Path $LOG -Value $s }
$NAME = 'E2ECHK5554d3g-key'
$TGT  = 'ssh -p 2222 sshtest@127.0.0.1'
$KEYP = '/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'
$PASS = 'keypass123'
$ANRN = 0
function Anr {
    for ($i = 0; $i -lt 4; $i++) {
        $f = (A shell "dumpsys window | grep mCurrentFocus" | Out-String)
        if ($f -notmatch 'Not Responding') { return }
        $script:ANRN++
        A shell "input tap 540 1406" | Out-Null; Start-Sleep -Milliseconds 800
        Log "ANR #$($script:ANRN) dismissed"
    }
}
function Snap($tag) { Anr; return (Nodes $tag) }
function Fld($n, [int]$y0, [int]$y1) {
    foreach ($f in ($n | Select-String -Pattern 'EditText\t')) {
        $r = RectOf $f.Line
        if ($r -and $r[0] -eq 138 -and $r[1] -ge $y0 -and $r[1] -le $y1) { return $f.Line }
    }
    return $null
}
function TypeS($t) { TypeText $t 8 700 }
AssertDevice; Anr

# ---------- navigate: Servers tab (tap whatever node is literally 'Servers') ----------
$onPanel = $false
for ($try = 0; $try -lt 4 -and -not $onPanel; $try++) {
    $n = Snap "w0_$try"
    if ($n | Select-String -Pattern 'Add SSH server' -Quiet) { $onPanel = $true; break }
    if ($n | Select-String -Pattern '^Settings\t' -Quiet) {
        $s = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
        $p = CenterOf $s.Line; Log "tap Settings @ $($p[0]),$($p[1])"
        A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
    }
    $n = Snap "w1_$try"
    $srv = $n | Select-String -Pattern '\tServers\t' | Select-Object -First 1
    if ($srv) {
        $p = CenterOf $srv.Line; Log "tap Servers @ $($p[0]),$($p[1])"
        A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
        $n = Snap "w2_$try"
        if ($n | Select-String -Pattern 'Add SSH server' -Quiet) { $onPanel = $true; break }
    }
    Log "nav try ${try}: panel not reached"
}
if (-not $onPanel) { Shot 'D3G_W_00_nav'; throw 'servers panel unreachable' }
Log 'servers panel open'

# ---------- open the Add form ----------
$n = Snap 'w3'
if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) {
    $add = $n | Select-String -Pattern 'Add SSH server' | Select-Object -First 1
    $p = CenterOf $add.Line; Log "tap Add SSH server @ $($p[0]),$($p[1])"
    A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
    $n = Snap 'w3b'
    if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) { Shot 'D3G_W_01_form'; throw 'form did not open' }
}
Log 'form open'
$n | Select-String -Pattern 'EditText\t|Server name|Authentication|^Password\t|^Private key\t|Add server' | ForEach-Object { Log "  $($_.Line)" }

# ---------- name + host ----------
$lab = $n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1
$lr = RectOf $lab.Line
$nf = Fld $n ($lr[3]) ($lr[3] + 250)
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
if (-not $nf -or -not $hf) { Shot 'D3G_W_02_fields'; throw 'fields missing' }
$np = CenterOf $nf
Anr; A shell "input tap $($np[0]) $($np[1])" | Out-Null; Start-Sleep -Milliseconds 700
Anr; TypeS $NAME; Start-Sleep -Milliseconds 250; Anr; CollapseIme
$n = Snap 'w4'
$lr = RectOf (($n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1).Line)
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
$hp = CenterOf $hf
Anr; A shell "input tap $($hp[0]) $($hp[1])" | Out-Null; Start-Sleep -Milliseconds 700
Anr; TypeS $TGT; Start-Sleep -Milliseconds 250; Anr; CollapseIme
Log "name+host typed n@$($np[0]),$($np[1]) h@$($hp[0]),$($hp[1])"
Shot 'D3G_W_03_named'

# ---------- auth chip via IME collapse ONLY ----------
$chip = $null
for ($i = 0; $i -lt 3 -and -not $chip; $i++) {
    Anr; CollapseIme
    $n = Snap "w5_$i"
    $chip = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if (-not $chip) { $chip = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1 }
}
if (-not $chip) {
    $n = Snap 'w5x'
    Log 'CHIP SEARCH FAILED. Visible text nodes:'
    $n | Select-String -Pattern 'TextView|Button' | ForEach-Object { Log "  $($_.Line)" }
    Shot 'D3G_W_04_nochip'
    Log 'ABORT: auth chip not reachable without dismissing the form'
    return
}
$cp = CenterOf $chip.Line
Log "tap chip '$((($chip.Line -split "`t")[0]))' @ $($cp[0]),$($cp[1])"
Anr; A shell "input tap $($cp[0]) $($cp[1])" | Out-Null; Start-Sleep -Seconds 1
$n = Snap 'w5b'
$pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if ($pk -and $pk.Line -ne $chip.Line) { $pp = CenterOf $pk.Line; Log "tap Private key @ $($pp[0]),$($pp[1])"; Anr; A shell "input tap $($pp[0]) $($pp[1])" | Out-Null; Start-Sleep -Seconds 1 }
Shot 'D3G_W_05_chip'
Log 'W PASS: chip selected, stopping before keypath (budget)'
