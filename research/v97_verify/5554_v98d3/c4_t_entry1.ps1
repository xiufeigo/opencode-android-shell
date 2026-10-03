. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir

function ImeShown {
    $o = & $global:ADB -s emulator-5554 shell "dumpsys input_method | grep -E 'mInputShown' | head -1"
    return ($o -join ' ').Trim()
}
function ImeTop {
    # top edge of the IME window, read from the dump
    $t = 99999
    foreach ($n in (Nodes)) {
        $pkg = $n.GetAttribute("package")
        if ($pkg -match 'inputmethod|ime' ) { $b = Bnd $n; if ($b -and $b['t'] -lt $t) { $t = $b['t'] } }
    }
    # also: a node whose class is a keyboard-ish view is unreliable; use window dump instead
    if ($t -eq 99999) { return "n/a" }
    return $t
}
function ImeBoundsFromWindow {
    $o = & $global:ADB -s emulator-5554 shell "dumpsys window windows | grep -E -A2 'InputMethod' | grep -E 'Frames|mFrame' | head -4"
    return ($o -join ' | ').Trim()
}

# form fields anchored on the inner ScrollView that contains them
function FormSV {
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("class") -ne "android.widget.ScrollView") { continue }
        $b = Bnd $n; if (-not $b) { continue }
        foreach ($e in (DumpET)) { $eb = Bnd $e
            if ($eb['t'] -ge $b['t'] - 2 -and $eb['b'] -le $b['b'] + 2) { return $b } }
    }
    return $null
}
function FormETs {
    $sv = FormSV; if (-not $sv) { return @() }
    $l = @()
    foreach ($n in (DumpET)) { $b = Bnd $n
        if ($b['t'] -lt $sv['t'] - 2) { continue }
        if ($b['b'] -gt $sv['b'] + 2) { continue }
        $l += ,@($n, $b) }
    return @($l | Sort-Object { $_[1]['t'] })
}
function ExposeRole([int]$i, [int]$tries = 6) {
    for ($k = 0; $k -lt $tries; $k++) {
        $l = FormETs
        if ($l.Count -gt $i -and ($l[$i][1]['b'] - $l[$i][1]['t']) -ge 100) { return $l[$i][0] }
        $sv = FormSV; if (-not $sv) { return $null }
        $cy = [int](($sv['t'] + $sv['b']) / 2)
        Say ("    form scroll up try{0} cy={1}" -f $k, $cy)
        FastScroll 800 $cy ($cy + 100) 80
        $null = Cap ("c4_sf_{0}_{1}" -f $i, $k)
    }
    $l = FormETs
    if ($l.Count -gt $i -and ($l[$i][1]['b'] - $l[$i][1]['t']) -ge 100) { return $l[$i][0] }
    return $null
}
# dismiss the IME by tapping a non-input TextView inside the form card (NEVER BACK / ESC)
function CloseIme([string]$tag) {
    $before = ImeShown
    $cands = @()
    foreach ($lbl in @("Host or SSH command","Add SSH server","Server name (optional)")) {
        foreach ($n in (Nodes)) {
            if ((TxtOf $n) -ne $lbl) { continue }
            if ($n.GetAttribute("class") -ne "android.widget.TextView") { continue }
            $b = Bnd $n
            if ($b['t'] -lt 900) { continue }   # must be the form's own label, not the panel header
            $cands += ,@($lbl, $b)
        }
    }
    Say ("  CloseIme[{0}] before={1} cands={2}" -f $tag, $before, $cands.Count)
    foreach ($c in $cands) {
        $b = $c[1]
        Say ("    tap label '{0}' @{1},{2}" -f $c[0], $b['cx'], $b['cy'])
        TapXY $b['cx'] $b['cy']
        $s = ImeShown
        Say ("    -> IME={0}" -f $s)
        if ($s -match 'false') { return $true }
    }
    # fall back: tap the form card's blank right margin (x=1020)
    $sv = FormSV
    if ($sv) { Say ("    tap blank @{0},{1}" -f 1020, [int](($sv['t']+$sv['b'])/2)); TapXY 1020 ([int](($sv['t']+$sv['b'])/2))
        Say ("    -> IME={0}" -f (ImeShown)) }
    $null = Cap ("c4_ime_{0}" -f $tag)
    return ((ImeShown) -match 'false')
}

$HSTR = "ssh -p 2222 sshtest@127.0.0.1"
$NM   = "E2ECHK5554d3c-1"

Say "=== 0) open form ==="
$null = Cap "c4_t0_pre"
if (-not (FormSV)) { $null = TapTxt "Add SSH server" 0; $null = Cap "c4_t1_open" }
$sv = FormSV
Say ("  FORM_SV=[{0},{1}][{2},{3}]" -f $sv['l'],$sv['t'],$sv['r'],$sv['b'])
Say ("  BTN_BEFORE " + (BtnState "Add server"))

Say "=== 1) fill host ==="
$f = ExposeRole 0
if (-not $f) { Say "  HOST_FAIL"; exit 1 }
$b = Bnd $f
Say ("  host [{0},{1}][{2},{3}] old='{4}'" -f $b['l'],$b['t'],$b['r'],$b['b'],(TxtOf $f))
TapXY $b['cx'] $b['cy']
if ((TxtOf $f)) { ClearET 60 }
TypeS $HSTR
$ok1 = CloseIme "host"
Say ("  host closeIme={0}" -f $ok1)

Say "=== 2) fill name ==="
$f2 = ExposeRole 1
if (-not $f2) { Say "  NAME_FAIL"; exit 1 }
$b2 = Bnd $f2
Say ("  name [{0},{1}][{2},{3}] old='{4}'" -f $b2['l'],$b2['t'],$b2['r'],$b2['b'],(TxtOf $f2))
TapXY $b2['cx'] $b2['cy']
if ((TxtOf $f2)) { ClearET 60 }
TypeS $NM
$ok2 = CloseIme "name"
Say ("  name closeIme={0}" -f $ok2)

Say "=== 3) verify sentinels + IME geometry ==="
foreach ($n in (DumpET)) { $bb = Bnd $n; $tt = TxtOf $n
    Say ("  ET [{0},{1}][{2},{3}] foc={4} | '{5}'" -f $bb['l'],$bb['t'],$bb['r'],$bb['b'],$n.GetAttribute("focused"),$tt) }
Say ("  IME_SHOWN=" + (ImeShown))
Say ("  IME_FRAMES=" + (ImeBoundsFromWindow))
$null = Cap "c4_t2_ready"
Say ("  BTN_AFTER " + (BtnState "Add server"))

Say "=== 4) submit ==="
$anc = ClickAncestor (FindN "Add server" 0)
$ab = Bnd $anc
Say ("  tap Add server anc @{0},{1} [{2},{3}][{4},{5}]" -f $ab['cx'],$ab['cy'],$ab['l'],$ab['t'],$ab['r'],$ab['b'])
TapXY $ab['cx'] $ab['cy']
$null = Cap "c4_t3_after"
$null = IsANR
Say "=== 5) what changed ==="
Say ("  FORM_STILL_OPEN=" + [bool](FormSV))
foreach ($n in (Nodes)) { $bb = Bnd $n; if (-not $bb) { continue }
    if ($bb['t'] -lt 500) { continue }
    $tt = TxtOf $n; if (-not $tt) { continue }
    Say ("  [{0},{1}][{2},{3}] {5} | {4}" -f $bb['l'],$bb['t'],$bb['r'],$bb['b'],$tt,$n.GetAttribute("class")) }
