# d3g_z.ps1 - form is ALREADY open. Fill it with ANR dismissal between every step.
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
# 'Wait' on the ANR dialog is at (540,1406) per ui_anr.xml. Never BACK/ESC, never
# force-stop com.minimax.coderemote (that is the harness hosting this session).
function Anr {
    for ($i = 0; $i -lt 3; $i++) {
        $f = (A shell "dumpsys window | grep mCurrentFocus" | Out-String)
        if ($f -notmatch 'Not Responding') { return $false }
        $script:ANRN++
        A shell "input tap 540 1406" | Out-Null
        Start-Sleep -Milliseconds 900
        Log "ANR #$($script:ANRN) dismissed"
    }
    return $false
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

$n = Snap 'z0'
$lab = $n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1
if (-not $lab) { $n | Select-String -Pattern 'EditText\t|Host or SSH|Add server' | ForEach-Object { Log "  $($_.Line)" }; Shot 'D3G_Z_00'; throw 'form not on screen' }
$lr = RectOf $lab.Line
$nf = Fld $n ($lr[3]) ($lr[3] + 250)
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
if (-not $nf -or -not $hf) { Shot 'D3G_Z_01'; throw 'fields missing' }

# ---- name ----
$np = CenterOf $nf
Anr; A shell "input tap $($np[0]) $($np[1])" | Out-Null; Start-Sleep -Milliseconds 800
Anr; TypeS $NAME; Start-Sleep -Milliseconds 300; Anr; CollapseIme
Log "name typed @ $($np[0]),$($np[1])"

# ---- host ----
$hp = CenterOf $hf
Anr; A shell "input tap $($hp[0]) $($hp[1])" | Out-Null; Start-Sleep -Milliseconds 800
Anr; TypeS $TGT; Start-Sleep -Milliseconds 300; Anr; CollapseIme
Log "host typed @ $($hp[0]),$($hp[1])"
Shot 'D3G_Z_02_named'

# ---- auth chip: find Password/Private key by scrolling the form body ----
$chip = $null
for ($i = 0; $i -lt 5 -and -not $chip; $i++) {
    $n = Snap "z1_$i"
    $chip = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if (-not $chip) { $chip = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1 }
    if (-not $chip) { Anr; A shell "input swipe 540 1620 540 1380 350" | Out-Null; Start-Sleep -Milliseconds 800 }
}
if (-not $chip) { Shot 'D3G_Z_03'; throw 'auth chip unreachable' }
$cp = CenterOf $chip.Line
Log "tap chip '$((($chip.Line -split "`t")[0]))' @ $($cp[0]),$($cp[1])"
Anr; A shell "input tap $($cp[0]) $($cp[1])" | Out-Null; Start-Sleep -Seconds 1
$n = Snap 'z1b'
$pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if ($pk -and $pk.Line -ne $chip.Line) { $pp = CenterOf $pk.Line; Log "tap Private key @ $($pp[0]),$($pp[1])"; Anr; A shell "input tap $($pp[0]) $($pp[1])" | Out-Null; Start-Sleep -Seconds 1 }
Shot 'D3G_Z_04_chip'

# ---- key path ----
$n = Snap 'z2'
$kl = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
$kr = RectOf $kl.Line
$kf = Fld $n ($kr[3]) ($kr[3] + 320)
for ($i = 0; $i -lt 3 -and -not $kf; $i++) {
    Anr; A shell "input swipe 540 1620 540 1380 350" | Out-Null; Start-Sleep -Milliseconds 800
    $n = Snap "z2s$i"
    $kl2 = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if ($kl2) { $kr = RectOf $kl2.Line; $kf = Fld $n ($kr[3]) ($kr[3] + 320) }
}
if (-not $kf) { Shot 'D3G_Z_05'; throw 'key path field not found' }
$kp = CenterOf $kf
Anr; A shell "input tap $($kp[0]) $($kp[1])" | Out-Null; Start-Sleep -Milliseconds 800
Anr; TypeS $KEYP; Start-Sleep -Milliseconds 300; Anr; CollapseIme
Log "keypath typed @ $($kp[0]),$($kp[1])"

# ---- passphrase ----
$pf = $null
for ($i = 0; $i -lt 4 -and -not $pf; $i++) {
    $n = Snap "z3_$i"
    $pf = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match 'pw=true' -and $_ -match '\t\[138,' } | Select-Object -First 1
    if (-not $pf) { Anr; A shell "input swipe 540 1620 540 1380 350" | Out-Null; Start-Sleep -Milliseconds 800 }
}
if ($pf) {
    $p2 = CenterOf $pf.Line
    Anr; A shell "input tap $($p2[0]) $($p2[1])" | Out-Null; Start-Sleep -Milliseconds 800
    Anr; TypeS $PASS; Start-Sleep -Milliseconds 300; Anr; CollapseIme
    Log "passphrase typed @ $($p2[0]),$($p2[1])"
} else { Log 'NO passphrase field' }
Shot 'D3G_Z_06_ready'

# ---- submit ----
$n = Snap 'z4'
$n | Select-String -Pattern 'EditText\t|^Add server\t|^Private key\t|^Password\t' | ForEach-Object { Log "  FLD $($_.Line)" }
$sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
if (-not $sub) { Shot 'D3G_Z_07'; throw 'Add server not visible' }
$sp = CenterOf $sub.Line
Log "SUBMIT @ $($sp[0]),$($sp[1])"
Anr; A shell "input tap $($sp[0]) $($sp[1])" | Out-Null
Start-Sleep -Seconds 3
Shot 'D3G_Z_08_submitted'
Log 'Z PASS DONE'
