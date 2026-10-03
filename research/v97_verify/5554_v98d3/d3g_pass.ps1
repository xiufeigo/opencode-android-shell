# d3g_pass.ps1 - ANR-guarded end-to-end: launch, nav, fill key entry, submit, connect, report.
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

# The host harness (com.minimax.coderemote) keeps throwing an ANR dialog that steals
# focus from the app under test. Never BACK/ESC: tap 'Wait'. Never force-stop it.
function ClearAnr {
    $f = (A shell "dumpsys window | grep mCurrentFocus" | Out-String)
    if ($f -match 'Not Responding') {
        $n = Nodes 'anr'
        $w = $n | Select-String -Pattern '^Wait\t' | Select-Object -First 1
        if ($w) { $p = CenterOf $w.Line; A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2; Log 'ANR cleared (Wait)' }
        return $true
    }
    return $false
}
function Dump($tag) { ClearAnr | Out-Null; return (Nodes $tag) }
function Fld($n, [int]$y0, [int]$y1) {
    foreach ($f in ($n | Select-String -Pattern 'EditText\t')) {
        $r = RectOf $f.Line
        if ($r -and $r[0] -eq 138 -and $r[1] -ge $y0 -and $r[1] -le $y1) { return $f.Line }
    }
    return $null
}
function TypeS($t) { TypeText $t 8 1000 }

AssertDevice
ClearAnr | Out-Null
ResetFaillock | Out-Null
A shell "am force-stop $global:PKG" | Out-Null; Start-Sleep -Seconds 1
A shell "am start -n $global:PKG/.OcActivity" | Out-Null
Start-Sleep -Seconds 9
Log "focus after launch: $((A shell 'dumpsys window | grep mCurrentFocus' | Out-String).Trim())"

# --- navigate to Servers (idempotent, with ANR guard) ---
for ($try = 0; $try -lt 3; $try++) {
    $n = Dump "nav$try"
    if ($n | Select-String -Pattern '^Settings\t' -Quiet) {
        $s = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
        $p = CenterOf $s.Line; A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
        Log 'settings opened'
    }
    $n = Dump "srv$try"
    if ($n | Select-String -Pattern '^Servers\t' -Quiet) {
        for ($k = 0; $k -lt 5; $k++) {
            $srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1
            if ($srv) { break }
            A shell "input swipe 950 744 150 744 350" | Out-Null; Start-Sleep -Milliseconds 700
            $n = Dump "srvsw$try$k"
        }
        $srv = $n | Select-String -Pattern '^Servers\t' | Select-Object -First 1
        if ($srv) { $p = CenterOf $srv.Line; A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 1; Log 'servers panel open'; break }
    }
    Log "nav attempt $try did not reach servers; retrying"
}
$n = Dump 'add0'
if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) {
    $add = $n | Select-String -Pattern '^Add SSH server\t' | Select-Object -First 1
    if (-not $add) { Shot 'D3G_P_00_navfail'; throw 'Add SSH server not found' }
    $p = CenterOf $add.Line; A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
    $n = Dump 'add1'
    if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) { Shot 'D3G_P_00_formfail'; throw 'form did not open' }
}
Log 'form open'

# ---- name ----
$n = Dump 'p1'
$lab = $n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1
if (-not $lab) { Shot 'D3G_P_01_nolab'; throw 'name label not on screen' }
$lr = RectOf $lab.Line
$nf = Fld $n ($lr[3]) ($lr[3] + 250)
if (-not $nf) { throw 'name field not found' }
$np = CenterOf $nf
A shell "input tap $($np[0]) $($np[1])" | Out-Null; Start-Sleep -Milliseconds 900
$c = Dump 'p1b'; if (-not ($c | Select-String -Pattern 'foc=true' -Quiet)) { A shell "input tap $($np[0]) $($np[1])" | Out-Null; Start-Sleep -Milliseconds 900 }
TypeS $NAME; Start-Sleep -Milliseconds 400; CollapseIme; Log "name typed @ $($np[0]),$($np[1])"

# ---- host ----
$n = Dump 'p2'
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
if (-not $hf) { Shot 'D3G_P_02_nohost'; throw 'host field not found' }
$hp = CenterOf $hf
A shell "input tap $($hp[0]) $($hp[1])" | Out-Null; Start-Sleep -Milliseconds 900
TypeS $TGT; Start-Sleep -Milliseconds 400; CollapseIme; Log "host typed @ $($hp[0]),$($hp[1])"
Shot 'D3G_P_03_named'

# ---- auth chip ----
$chip = $null
for ($i = 0; $i -lt 4 -and -not $chip; $i++) {
    $n = Dump "pc$i"
    $chip = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if (-not $chip) { $chip = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1 }
    if (-not $chip) { if ($i -eq 0) { CollapseIme } else { A shell "input swipe 540 1650 540 1350 400" | Out-Null; Start-Sleep -Milliseconds 900 } }
}
if (-not $chip) { Shot 'D3G_P_04_nochip'; throw 'auth chip unreachable' }
$cp = CenterOf $chip.Line
Log "tap chip '$((($chip.Line -split "`t")[0]))' @ $($cp[0]),$($cp[1])"
A shell "input tap $($cp[0]) $($cp[1])" | Out-Null; Start-Sleep -Seconds 1
$n = Dump 'pc9'
$pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if ($pk -and $pk.Line -ne $chip.Line) { $pp = CenterOf $pk.Line; Log "tap Private key @ $($pp[0]),$($pp[1])"; A shell "input tap $($pp[0]) $($pp[1])" | Out-Null; Start-Sleep -Seconds 1 }
Shot 'D3G_P_05_chip'

# ---- key path ----
$n = Dump 'p3'
$kl = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if (-not $kl) { Shot 'D3G_P_06_nokeylbl'; throw 'Private key label gone' }
$kr = RectOf $kl.Line
$kf = Fld $n ($kr[3]) ($kr[3] + 300)
if (-not $kf) { for ($i=0;$i -lt 3 -and -not $kf;$i++){ A shell "input swipe 540 1650 540 1350 400" | Out-Null; Start-Sleep -Milliseconds 900; $n = Dump "pk$i"; $kr = RectOf (($n | Select-String -Pattern '^Private key\t' | Select-Object -First 1).Line); $kf = Fld $n ($kr[3]) ($kr[3] + 300) } }
if (-not $kf) { Shot 'D3G_P_07_nokeyf'; throw 'key path field not found' }
$kp = CenterOf $kf
A shell "input tap $($kp[0]) $($kp[1])" | Out-Null; Start-Sleep -Milliseconds 900
TypeS $KEYP; Start-Sleep -Milliseconds 400; CollapseIme; Log "keypath typed @ $($kp[0]),$($kp[1])"

# ---- passphrase ----
$pf = $null
for ($i = 0; $i -lt 4 -and -not $pf; $i++) {
    $n = Dump "pp$i"
    $pf = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'pw=true' -and $_ -match '\t\[138,' } | Select-Object -First 1
    if (-not $pf) { if ($i -eq 0) { CollapseIme } else { A shell "input swipe 540 1650 540 1350 400" | Out-Null; Start-Sleep -Milliseconds 900 } }
}
if ($pf) {
    $p2 = CenterOf $pf.Line
    A shell "input tap $($p2[0]) $($p2[1])" | Out-Null; Start-Sleep -Milliseconds 900
    TypeS $PASS; Start-Sleep -Milliseconds 400; CollapseIme; Log "passphrase typed @ $($p2[0]),$($p2[1])"
} else { Log 'NO passphrase field' }
Shot 'D3G_P_08_ready'
$n = Dump 'p4'
$n | Select-String -Pattern 'EditText\t|^Add server\t|^Private key\t|^Password\t' | ForEach-Object { Log "  FLD $($_.Line)" }
$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if (-not $sub) { Shot 'D3G_P_09_nosubmit'; throw 'Add server not visible' }
$sp = CenterOf $sub.Line
Log "SUBMIT @ $($sp[0]),$($sp[1])"
A shell "input tap $($sp[0]) $($sp[1])" | Out-Null
Start-Sleep -Seconds 3
Shot 'D3G_P_10_submitted'
Log 'PASS DONE'
