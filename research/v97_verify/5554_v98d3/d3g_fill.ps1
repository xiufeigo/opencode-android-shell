# d3g_fill.ps1 - direct, dump-driven form fill for the D3-4 closeout round.
param(
    [string]$Dev = 'emulator-5554',
    [string]$Name = 'E2ECHK5554d3g-key',
    [string]$Tgt = 'ssh -p 2222 sshtest@127.0.0.1',
    [string]$KeyPath = '/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519',
    [string]$Pass = 'keypass123',
    [switch]$NoSubmit
)
$ErrorActionPreference = 'Stop'
$HELP = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554'
. "$HELP\E2ECHK5554_run.ps1" -Dev $Dev
$global:DIR = 'C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3'
$LOG = Join-Path $global:DIR 'd3g_run.log'
function Log($m) { $s = "[{0:HH:mm:ss}] {1}" -f (Get-Date), $m; Write-Host $s; Add-Content -Path $LOG -Value $s }

# fast tap: tap, then confirm SOME EditText has focus; retry up to 3x
function TapFocus($x, $y, $what) {
    for ($i = 1; $i -le 3; $i++) {
        A shell "input tap $x $y" | Out-Null
        Start-Sleep -Milliseconds 900
        $n = Nodes "f_$what$i"
        $f = $n | Select-String -Pattern 'foc=true' | Select-Object -First 1
        if ($f) { Log "  FOCUSED $what @ $x,$y"; return $true }
        Log "  $what not focused (try $i)"
    }
    return $false
}
function TypeS($t) { TypeText $t 8 1200 }

Log "FILL start name=$Name host=$Tgt"

# --- 1. host field: first non-search EditText (search box starts at x=96) ---
$n = Nodes 'f_h0'
$hostFld = $n | Select-String -Pattern 'EditText\t' | Where-Object { $_ -match '\t\[138,' } | Select-Object -First 1
if (-not $hostFld) { throw 'host field not found' }
$hp = CenterOf $hostFld.Line
Log "host field $($hostFld.Line)"
if (-not (TapFocus $hp[0] $hp[1] 'host')) { throw 'host never focused' }
TypeS $Tgt
Start-Sleep -Milliseconds 600
CollapseIme
Log 'host typed'

# --- 2. name field: the field directly under 'Server name (optional)' ---
$n = Nodes 'f_n0'
$res = ScrollToNear 'Server name (optional)' 'name' 'below'
if (-not $res) { throw 'name field unreachable' }
$np = CenterOf $res[0]
Log "name field @ $($np[0]),$($np[1])"
if (-not (TapFocus $np[0] $np[1] 'name')) { throw 'name never focused' }
TypeS $Name
Start-Sleep -Milliseconds 600
CollapseIme
Log 'name typed'
Shot 'D3G_F_01_named'

# --- 3. Private key chip (may need a form scroll; chips sit under the Authentication label) ---
$chip = $null
for ($i = 0; $i -lt 8 -and -not $chip; $i++) {
    $n = Nodes "f_c$i"
    $chip = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
    if (-not $chip) {
        $pw = $n | Select-String -Pattern '^Password\t' | Select-Object -First 1
        if ($pw) { $chip = $pw; Log '  (using Password chip as toggle target)' }
    }
    if (-not $chip) {
        if ($i -eq 2 -or $i -eq 5) { CollapseIme } else { FormSwipe $n -1 }
    }
}
if (-not $chip) { throw 'auth chip (Password/Private key) not reachable' }
$cp = CenterOf $chip.Line
Log "tap auth chip '$((($chip.Line -split "`t")[0]))' @ $($cp[0]),$($cp[1])"
A shell "input tap $($cp[0]) $($cp[1])" | Out-Null
Start-Sleep -Seconds 1

# after toggle, prefer the Private key option explicitly
$n = Nodes 'f_c9'
$pk = $n | Select-String -Pattern '^Private key\t' | Select-Object -First 1
if ($pk) { $pp = CenterOf $pk.Line; Log "tap Private key option @ $($pp[0]),$($pp[1])"; A shell "input tap $($pp[0]) $($pp[1])" | Out-Null; Start-Sleep -Seconds 1 }

# --- 4. key path ---
$res = ScrollToNear 'Private key' 'keypath' 'below'
if (-not $res) { throw 'key path field unreachable' }
$kp = CenterOf $res[0]
Log "keypath @ $($kp[0]),$($kp[1])"
if (-not (TapFocus $kp[0] $kp[1] 'keypath')) { throw 'keypath never focused' }
TypeS $KeyPath
Start-Sleep -Milliseconds 600
CollapseIme
Log 'keypath typed'

# --- 5. passphrase (pw=true field) ---
$res = ScrollToPw 'pass'
if (-not $res) { Log '  no pw field found (passphrase may be optional)'; }
else {
    $pp2 = CenterOf $res[0]
    Log "passphrase @ $($pp2[0]),$($pp2[1])"
    if (TapFocus $pp2[0] $pp2[1] 'pass') { TypeS $Pass; Start-Sleep -Milliseconds 600; CollapseIme }
}
Shot 'D3G_F_02_ready'
$n = Nodes 'f_ready'
Log '--- form state ---'
$n | Select-String -Pattern 'EditText\t|^Add server\t|^Private key\t|^Password\t|^Passphrase\t' | ForEach-Object { Log "  $($_.Line)" }

if (-not $NoSubmit) {
    $n = Nodes 'f_sub'
    $sub = $n | Select-String -Pattern '^Add server\t' | Select-Object -First 1
    if (-not $sub) { throw 'Add server not visible' }
    Log "SUBMIT line: $($sub.Line)"
    $sp = CenterOf $sub.Line
    A shell "input tap $($sp[0]) $($sp[1])" | Out-Null
    Start-Sleep -Seconds 2
    Log "submitted @ $($sp[0]),$($sp[1])"
}
Log 'FILL DONE'

