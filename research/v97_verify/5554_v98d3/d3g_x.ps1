# d3g_x.ps1 - direct continuation from the known state: Servers tab is on screen.
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
    $f = (A shell "dumpsys window | grep mCurrentFocus" | Out-String)
    if ($f -match 'Not Responding') { $script:ANRN++; A shell "input tap 540 1406" | Out-Null; Start-Sleep -Milliseconds 1000; Log "ANR #$($script:ANRN) dismissed" }
}
function Snap($tag) { Anr; return (Nodes $tag) }
function Fld($n, [int]$y0, [int]$y1) {
    foreach ($f in ($n | Select-String -Pattern 'EditText\t')) {
        $r = RectOf $f.Line
        if ($r -and $r[0] -eq 138 -and $r[1] -ge $y0 -and $r[1] -le $y1) { return $f.Line }
    }
    return $null
}
function TypeS($t) { TypeText $t 8 800 }
AssertDevice; Anr

# --- 1. Servers tab (no swipe: it is already on screen) ---
$n = Snap 'x1'
$all = $n | Select-String -Pattern 'Servers'
Log "Servers node lines: $($all.Count)"
$all | ForEach-Object { Log "  $($_.Line)" }
$srv = $n | Select-String -Pattern '\tServers\t' | Select-Object -First 1
if (-not $srv) { $srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1 }
if (-not $srv) { Shot 'D3G_X_00'; throw 'Servers tab node not found' }
$p = CenterOf $srv.Line
Log "tap Servers @ $($p[0]),$($p[1])"
A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
$n = Snap 'x2'
$n | Select-String -Pattern 'Add SSH server|Servers\t' | ForEach-Object { Log "  $($_.Line)" }

# --- 2. Add SSH server form ---
if (-not ($n | Select-String -Pattern 'Add SSH server' -Quiet)) { Shot 'D3G_X_01'; throw 'servers panel did not open' }
$add = $n | Select-String -Pattern '^Add SSH server\t' | Select-Object -First 1
$p = CenterOf $add.Line
Log "tap Add SSH server @ $($p[0]),$($p[1])"
A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
$n = Snap 'x3'
if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) { Shot 'D3G_X_02'; throw 'form did not open' }
Log 'form open'
$n | Select-String -Pattern 'EditText\t|Server name|Authentication|^Password\t|^Private key\t|Add server' | ForEach-Object { Log "  $($_.Line)" }

# --- 3. name + host ---
$lab = $n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1
$lr = RectOf $lab.Line
$nf = Fld $n ($lr[3]) ($lr[3] + 250)
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
if (-not $nf -or -not $hf) { Shot 'D3G_X_03'; throw "fields missing" }
$np = CenterOf $nf
A shell "input tap $($np[0]) $($np[1])" | Out-Null; Start-Sleep -Milliseconds 800
TypeS $NAME; Start-Sleep -Milliseconds 300; CollapseIme
$n = Snap 'x4'
$lr = RectOf (($n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1).Line)
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
$hp = CenterOf $hf
A shell "input tap $($hp[0]) $($hp[1])" | Out-Null; Start-Sleep -Milliseconds 800
TypeS $TGT; Start-Sleep -Milliseconds 300; CollapseIme
Log "name+host typed (name@$($np[0]),$($np[1]) host@$($hp[0]),$($hp[1]))"
Shot 'D3G_X_04_named'

# --- 4. auth chip ---
$chip = $null
for ($i = 0; $i -lt 4 -and -not $chip; $i++) {
    $n = Snap "x5_$i"
    $chip = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if (-not $chip) { $chip = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1 }
    if (-not $chip) { A shell "input swipe 540 1650 540 1400 350" | Out-Null; Start-Sleep -Milliseconds 900 }
}
if (-not $chip) { Shot 'D3G_X_05'; throw 'auth chip unreachable' }
$cp = CenterOf $chip.Line
Log "tap chip '$((($chip.Line -split "`t")[0]))' @ $($cp[0]),$($cp[1])"
A shell "input tap $($cp[0]) $($cp[1])" | Out-Null; Start-Sleep -Seconds 1
$n = Snap 'x5b'
$pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if ($pk -and $pk.Line -ne $chip.Line) { $pp = CenterOf $pk.Line; Log "tap Private key @ $($pp[0]),$($pp[1])"; A shell "input tap $($pp[0]) $($pp[1])" | Out-Null; Start-Sleep -Seconds 1 }
Shot 'D3G_X_06_chip'

# --- 5. key path ---
$n = Snap 'x6'
$kl = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if (-not $kl) { Shot 'D3G_X_07'; throw 'Private key label gone' }
$kr = RectOf $kl.Line
$kf = Fld $n ($kr[3]) ($kr[3] + 300)
for ($i = 0; $i -lt 3 -and -not $kf; $i++) {
    A shell "input swipe 540 1650 540 1400 350" | Out-Null; Start-Sleep -Milliseconds 900
    $n = Snap "x6s$i"
    $kl2 = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if ($kl2) { $kr = RectOf $kl2.Line; $kf = Fld $n ($kr[3]) ($kr[3] + 300) }
}
if (-not $kf) { Shot 'D3G_X_08'; throw 'key path field not found' }
$kp = CenterOf $kf
A shell "input tap $($kp[0]) $($kp[1])" | Out-Null; Start-Sleep -Milliseconds 800
TypeS $KEYP; Start-Sleep -Milliseconds 300; CollapseIme
Log "keypath typed @ $($kp[0]),$($kp[1])"

# --- 6. passphrase ---
$pf = $null
for ($i = 0; $i -lt 4 -and -not $pf; $i++) {
    $n = Snap "x7_$i"
    $pf = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'pw=true' -and $_ -match '\t\[138,' } | Select-Object -First 1
    if (-not $pf) { A shell "input swipe 540 1650 540 1400 350" | Out-Null; Start-Sleep -Milliseconds 900 }
}
if ($pf) {
    $p2 = CenterOf $pf.Line
    A shell "input tap $($p2[0]) $($p2[1])" | Out-Null; Start-Sleep -Milliseconds 800
    TypeS $PASS; Start-Sleep -Milliseconds 300; CollapseIme
    Log "passphrase typed @ $($p2[0]),$($p2[1])"
} else { Log 'NO passphrase field' }
Shot 'D3G_X_09_ready'
$n = Snap 'x8'
$n | Select-String -Pattern 'EditText\t|^Add server\t|^Private key\t|^Password\t' | ForEach-Object { Log "  FLD $($_.Line)" }
$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if (-not $sub) { Shot 'D3G_X_10'; throw 'Add server not visible' }
$sp = CenterOf $sub.Line
Log "SUBMIT @ $($sp[0]),$($sp[1])"
A shell "input tap $($sp[0]) $($sp[1])" | Out-Null
Start-Sleep -Seconds 3
Shot 'D3G_X_11_submitted'
Log 'X PASS DONE'
