# d3g_fast.ps1 - minimal-adb-roundtrip pass. The host harness (com.minimax.coderemote)
# throws an ANR dialog every ~30-60 s that steals focus from the app under test.
# Strategy: dump ONLY at assertion points; between them use taps resolved from the
# most recent dump. Dismiss the ANR with 'Wait' (never BACK/ESC, never force-stop it).
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
$WAIT = @(540,1406)   # ANR 'Wait' button, from ui_anr.xml dump
$ANRN = 0

function Anr {
    $f = (A shell "dumpsys window | grep mCurrentFocus" | Out-String)
    if ($f -match 'Not Responding') {
        $script:ANRN++
        A shell "input tap $($WAIT[0]) $($WAIT[1])" | Out-Null
        Start-Sleep -Milliseconds 1200
        Log "ANR #$($script:ANRN) dismissed"
        return $true
    }
    return $false
}
# dump, auto-clearing a blocking ANR first and re-dumping if one was in the way
function Snap($tag) {
    for ($i = 0; $i -lt 3; $i++) {
        Anr | Out-Null
        $n = Nodes $tag
        if ($n | Select-String -Pattern 'Not Responding|isn''t responding' -Quiet) { continue }
        return $n
    }
    return (Nodes $tag)
}
function Fld($n, [int]$y0, [int]$y1) {
    foreach ($f in ($n | Select-String -Pattern 'EditText\t')) {
        $r = RectOf $f.Line
        if ($r -and $r[0] -eq 138 -and $r[1] -ge $y0 -and $r[1] -le $y1) { return $f.Line }
    }
    return $null
}
function TypeS($t) { TypeText $t 8 900 }
AssertDevice
Anr | Out-Null

# ---------- 1. home -> Settings ----------
$n = Snap 'f1'
$st = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
if (-not $st) { Shot 'D3G_FST_00'; throw 'no Settings on home' }
$p = CenterOf $st.Line
Log "tap Settings @ $($p[0]),$($p[1])"
A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2

# ---------- 2. Servers tab ----------
$ok = $false
for ($i = 0; $i -lt 4 -and -not $ok; $i++) {
    $n = Snap "f2_$i"
    $srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1
    if (-not $srv) { A shell "input swipe 950 744 150 744 350" | Out-Null; Start-Sleep -Milliseconds 800; continue }
    $p = CenterOf $srv.Line
    A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 1
    $n = Snap "f2ok_$i"
    $ok = [bool]($n | Select-String -Pattern 'Add SSH server' -Quiet)
    Log "servers tab tap $i -> panel=$ok"
}
if (-not $ok) { Shot 'D3G_FST_01'; throw 'servers panel not open' }

# ---------- 3. Add SSH server form ----------
$n = Snap 'f3'
if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) {
    $add = $n | Select-String -Pattern '^Add SSH server\t' | Select-Object -First 1
    if (-not $add) { Shot 'D3G_FST_02'; throw 'Add SSH server not found' }
    $p = CenterOf $add.Line
    A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
    $n = Snap 'f3b'
    if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) { Shot 'D3G_FST_03'; throw 'form did not open' }
}
Log 'form open'

# ---------- 4. name + host (both visible together; no scroll needed) ----------
$lab = $n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1
$lr = RectOf $lab.Line
$nf = Fld $n ($lr[3]) ($lr[3] + 250)
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
if (-not $nf -or -not $hf) { Shot 'D3G_FST_04'; throw "fields missing nf=$([bool]$nf) hf=$([bool]$hf)" }
$np = CenterOf $nf; $hp = CenterOf $hf
A shell "input tap $($np[0]) $($np[1])" | Out-Null; Start-Sleep -Milliseconds 800
TypeS $NAME; Start-Sleep -Milliseconds 300; CollapseIme
$n = Snap 'f4'
$lab = $n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1
$lr = RectOf $lab.Line
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
$hp = CenterOf $hf
A shell "input tap $($hp[0]) $($hp[1])" | Out-Null; Start-Sleep -Milliseconds 800
TypeS $TGT; Start-Sleep -Milliseconds 300; CollapseIme
Log 'name+host typed'
Shot 'D3G_FST_05_named'

# ---------- 5. auth chip ----------
$chip = $null
for ($i = 0; $i -lt 4 -and -not $chip; $i++) {
    $n = Snap "f5_$i"
    $chip = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if (-not $chip) { $chip = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1 }
    if (-not $chip) { A shell "input swipe 540 1650 540 1400 350" | Out-Null; Start-Sleep -Milliseconds 900 }
}
if (-not $chip) { Shot 'D3G_FST_06'; throw 'auth chip unreachable' }
$cp = CenterOf $chip.Line
Log "tap chip '$((($chip.Line -split "`t")[0]))' @ $($cp[0]),$($cp[1])"
A shell "input tap $($cp[0]) $($cp[1])" | Out-Null; Start-Sleep -Seconds 1
$n = Snap 'f5b'
$pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if ($pk -and $pk.Line -ne $chip.Line) { $pp = CenterOf $pk.Line; Log "tap Private key @ $($pp[0]),$($pp[1])"; A shell "input tap $($pp[0]) $($pp[1])" | Out-Null; Start-Sleep -Seconds 1 }
Shot 'D3G_FST_07_chip'
Log 'FAST PASS: form portion done'
