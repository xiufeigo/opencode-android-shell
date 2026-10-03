# d3g_run.ps1 - one tight end-to-end pass: dismiss ANR, nav, fill, submit, connect, report.
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

AssertDevice
# dismiss any ANR dialog (Wait, never Close app / never BACK)
$focus = (A shell "dumpsys window | grep mCurrentFocus" | Out-String)
if ($focus -match 'Not Responding') {
    Log "ANR present: $($focus.Trim())"
    $n = Nodes 'anr'
    $w = $n | Select-String -Pattern '^Wait\t' | Select-Object -First 1
    if ($w) { $p = CenterOf $w.Line; A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2; Log 'ANR dismissed (Wait)' }
}

ResetFaillock | Out-Null
LaunchApp | ForEach-Object { Log "launch: $_" }
GotoServers
OpenAddForm
Log 'form open'

function Fld($n, [int]$y0, [int]$y1) {
    foreach ($f in ($n | Select-String -Pattern 'EditText\t')) {
        $r = RectOf $f.Line
        if ($r -and $r[0] -eq 138 -and $r[1] -ge $y0 -and $r[1] -le $y1) { return $f.Line }
    }
    return $null
}
function TypeS($t) { TypeText $t 8 1100 }

# ---- name: field directly under the 'Server name (optional)' label ----
$n = Nodes 'r1'
$lab = $n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1
if (-not $lab) { throw 'name label not on screen' }
$lr = RectOf $lab.Line
$nf = Fld $n ($lr[3]) ($lr[3] + 250)
if (-not $nf) { throw "name field not found under label y=$($lr[3])" }
$np = CenterOf $nf
Log "NAME field $($np[0]),$($np[1])"
A shell "input tap $($np[0]) $($np[1])" | Out-Null; Start-Sleep -Milliseconds 900
$chk = Nodes 'r1b'
if (-not ($chk | Select-String -Pattern 'foc=true')) { Log '  name tap missed focus, retry'; A shell "input tap $($np[0]) $($np[1])" | Out-Null; Start-Sleep -Milliseconds 900 }
TypeS $NAME
Start-Sleep -Milliseconds 500
CollapseIme
Log 'name typed'

# ---- host: the other non-search EditText above the name label ----
$n = Nodes 'r2'
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
if (-not $hf) { throw 'host field not found' }
$hp = CenterOf $hf
Log "HOST field $($hp[0]),$($hp[1])"
A shell "input tap $($hp[0]) $($hp[1])" | Out-Null; Start-Sleep -Milliseconds 900
TypeS $TGT
Start-Sleep -Milliseconds 500
CollapseIme
Log 'host typed'
Shot 'D3G_R_01_named'

# ---- auth chip: reveal by IME-collapse, else ONE short in-body swipe ----
$chip = $null
for ($i = 0; $i -lt 4 -and -not $chip; $i++) {
    $n = Nodes "r_c$i"
    $chip = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if (-not $chip) { $chip = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1 }
    if (-not $chip) {
        if ($i -eq 0) { CollapseIme }
        else { A shell "input swipe 540 1650 540 1350 400" | Out-Null; Start-Sleep -Milliseconds 900 }
    }
}
if (-not $chip) { Log 'AUTH CHIPS NOT REACHABLE'; Shot 'D3G_R_02_nochip'; throw 'auth chip unreachable' }
$cp = CenterOf $chip.Line
Log "tap chip '$((($chip.Line -split "`t")[0]))' @ $($cp[0]),$($cp[1])"
A shell "input tap $($cp[0]) $($cp[1])" | Out-Null; Start-Sleep -Seconds 1
$n = Nodes 'r_c9'
$pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if ($pk -and $pk.Line -ne $chip.Line) { $pp = CenterOf $pk.Line; Log "tap Private key option @ $($pp[0]),$($pp[1])"; A shell "input tap $($pp[0]) $($pp[1])" | Out-Null; Start-Sleep -Seconds 1 }
Shot 'D3G_R_02_chip'

# ---- key path under the 'Private key' label ----
$n = Nodes 'r3'
$kl = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if (-not $kl) { throw 'Private key label gone after toggle' }
$kr = RectOf $kl.Line
$kf = Fld $n ($kr[3]) ($kr[3] + 300)
if (-not $kf) { throw "key path field not found under label y=$($kr[3])" }
$kp = CenterOf $kf
Log "KEYPATH field $($kp[0]),$($kp[1])"
A shell "input tap $($kp[0]) $($kp[1])" | Out-Null; Start-Sleep -Milliseconds 900
TypeS $KEYP
Start-Sleep -Milliseconds 500
CollapseIme
Log 'keypath typed'

# ---- passphrase = the pw=true EditText ----
for ($i = 0; $i -lt 4; $i++) {
    $n = Nodes "r_p$i"
    $pf = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'pw=true' -and $_ -match '\t\[138,' } | Select-Object -First 1
    if ($pf) { break }
    if ($i -eq 0) { CollapseIme } else { A shell "input swipe 540 1650 540 1350 400" | Out-Null; Start-Sleep -Milliseconds 900 }
}
if ($pf) {
    $pp2 = CenterOf $pf.Line
    Log "PASSPHRASE field $($pp2[0]),$($pp2[1])"
    A shell "input tap $($pp2[0]) $($pp2[1])" | Out-Null; Start-Sleep -Milliseconds 900
    TypeS $PASS
    Start-Sleep -Milliseconds 500
    CollapseIme
    Log 'passphrase typed'
} else { Log 'no passphrase field found' }
Shot 'D3G_R_03_ready'

# ---- submit ----
$n = Nodes 'r4'
$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if (-not $sub) { throw 'Add server not visible' }
$sp = CenterOf $sub.Line
Log "SUBMIT @ $($sp[0]),$($sp[1])  line=$($sub.Line)"
A shell "input tap $($sp[0]) $($sp[1])" | Out-Null
Start-Sleep -Seconds 3
Log 'submitted'
Shot 'D3G_R_04_submitted'
Log 'RUN PASS DONE'
