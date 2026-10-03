# d3g_final.ps1 - last attempt. The host harness (com.minimax.coderemote) is
# ANR-looping and stealing the foreground, so this script re-asserts the app
# under test into the foreground before every navigation step.
param([string]$Dev = 'emulator-5554')
$ErrorActionPreference = 'Continue'
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
        A shell "input tap 540 1406" | Out-Null; Start-Sleep -Milliseconds 700
        Log "ANR #$($script:ANRN) dismissed"
    }
}
function Foreground {
    Anr
    $f = (A shell "dumpsys window | grep mCurrentFocus" | Out-String)
    if ($f -notmatch 'opencodeshell') {
        Log 'app not foreground -> am start'
        A shell "am start -n $global:PKG/.OcActivity" | Out-Null
        Start-Sleep -Seconds 5
        Anr
    }
}
function Snap($tag) { Foreground; return (Nodes $tag) }
function Fld($n, [int]$y0, [int]$y1) {
    foreach ($f in ($n | Select-String -Pattern 'EditText\t')) {
        $r = RectOf $f.Line
        if ($r -and $r[0] -eq 138 -and $r[1] -ge $y0 -and $r[1] -le $y1) { return $f.Line }
    }
    return $null
}
function TypeS($t) { TypeText $t 8 600 }
AssertDevice; Anr
ResetFaillock | Out-Null

$onPanel = $false
for ($try = 0; $try -lt 5 -and -not $onPanel; $try++) {
    $n = Snap "y0_$try"
    if ($n | Select-String -Pattern 'Add SSH server' -Quiet) { $onPanel = $true; break }
    if ($n | Select-String -Pattern '^Settings\t' -Quiet) {
        $s = $n | Select-String -Pattern '^Settings\t' | Select-Object -First 1
        $p = CenterOf $s.Line; Log "tap Settings @ $($p[0]),$($p[1])"
        A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
    }
    $n = Snap "y1_$try"
    $srv = $n | Select-String -Pattern '\tServers\t' | Select-Object -First 1
    if ($srv) {
        $p = CenterOf $srv.Line; Log "tap Servers @ $($p[0]),$($p[1])"
        A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
        if ((Snap "y2_$try") | Select-String -Pattern 'Add SSH server' -Quiet) { $onPanel = $true; break }
    }
    Log "nav try ${try}: no panel"
}
if (-not $onPanel) { Shot 'D3G_Y_00_nav'; Log 'NAV FAILED'; return }
Log 'panel open'
$n = Snap 'y3'
if (-not ($n | Select-String -Pattern 'Host or SSH command' -Quiet)) {
    $add = $n | Select-String -Pattern 'Add SSH server' | Select-Object -First 1
    $p = CenterOf $add.Line; Log "tap Add SSH server @ $($p[0]),$($p[1])"
    A shell "input tap $($p[0]) $($p[1])" | Out-Null; Start-Sleep -Seconds 2
    if (-not ((Snap 'y3b') | Select-String -Pattern 'Host or SSH command' -Quiet)) { Shot 'D3G_Y_01_form'; Log 'FORM FAILED'; return }
}
Log 'form open'
$n = Snap 'y4'
$lab = $n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1
$lr = RectOf $lab.Line
$nf = Fld $n ($lr[3]) ($lr[3] + 250)
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
if (-not $nf -or -not $hf) { Shot 'D3G_Y_02_fields'; Log 'FIELDS MISSING'; return }
$np = CenterOf $nf
Foreground; A shell "input tap $($np[0]) $($np[1])" | Out-Null; Start-Sleep -Milliseconds 700
Foreground; TypeS $NAME; Start-Sleep -Milliseconds 200; CollapseIme
$n = Snap 'y5'
$lr = RectOf (($n | Select-String -Pattern '^Server name \(optional\)\t' | Select-Object -First 1).Line)
$hf = Fld $n ($lr[3] - 700) ($lr[3] - 60)
$hp = CenterOf $hf
Foreground; A shell "input tap $($hp[0]) $($hp[1])" | Out-Null; Start-Sleep -Milliseconds 700
Foreground; TypeS $TGT; Start-Sleep -Milliseconds 200; CollapseIme
Log "typed name@$($np[0]),$($np[1]) host@$($hp[0]),$($hp[1])"
Shot 'D3G_Y_03_named'
$n = Snap 'y6'
$n | Select-String -Pattern 'EditText\t|^Password\t|^Private key\t|^Authentication\t|Add server' | ForEach-Object { Log "  $($_.Line)" }
$chip = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if (-not $chip) { $chip = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1 }
if ($chip) {
    $cp = CenterOf $chip.Line
    Log "tap chip '$((($chip.Line -split "`t")[0]))' @ $($cp[0]),$($cp[1])"
    Foreground; A shell "input tap $($cp[0]) $($cp[1])" | Out-Null; Start-Sleep -Seconds 1
    $n = Snap 'y7'
    $pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if ($pk -and $pk.Line -ne $chip.Line) { $pp = CenterOf $pk.Line; Log "tap Private key @ $($pp[0]),$($pp[1])"; A shell "input tap $($pp[0]) $($pp[1])" | Out-Null; Start-Sleep -Seconds 1 }
    Shot 'D3G_Y_04_chip'
} else { Shot 'D3G_Y_05_nochip'; Log 'CHIP NOT VISIBLE' }
Log 'FINAL ATTEMPT END'
