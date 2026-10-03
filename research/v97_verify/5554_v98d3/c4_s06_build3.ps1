. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir

# Form EditTexts, y-window [1250,1950]; ONLY valid when the IME is DOWN.
# Strategy: drop the IME after every field so all geometry returns to this baseline.
function FormETs {
    $list = @()
    foreach ($n in (DumpET)) {
        $b = Bnd $n
        if ($b['t'] -lt 1250) { continue }
        if ($b['t'] -gt 1950) { continue }
        $list += ,@($n, $b)
    }
    return @($list | Sort-Object { $_[1]['t'] })
}
function RoleName([int]$i) { if ($i -eq 0) { "Host or SSH command" } elseif ($i -eq 1) { "Server name (optional)" } else { "Password" } }

function ShowFormETs([string]$tag) {
    $l = FormETs
    Say "  ---- form ETs [$tag] n=$($l.Count) ----"
    for ($i = 0; $i -lt $l.Count; $i++) {
        $b = $l[$i][1]
        Say ("    role{0} '{1}' [{2},{3}][{4},{5}] h={6} @{7},{8} text='{9}'" -f `
            $i, (RoleName $i), $b['l'],$b['t'],$b['r'],$b['b'], ($b['b']-$b['t']), $b['cx'],$b['cy'], (TxtOf $l[$i][0]))
    }
}

function ImeShown {
    $o = & $global:ADB -s emulator-5554 shell "dumpsys input_method | grep -E 'mInputShown' | head -1"
    return ($o -join ' ').Trim()
}

# drop IME by tapping neutral non-field area inside the form card (NEVER BACK / ESC)
function DropIme([string]$tag) {
    TapXY 995 1430
    $null = Cap ("c4_ime_{0}" -f $tag)
    $s = ImeShown
    Say ("  IME[{0}] = {1}" -f $tag, $s)
    return $s
}

function ExposeRole([int]$i, [int]$dir, [int]$tries = 5) {
    for ($k = 0; $k -lt $tries; $k++) {
        $l = FormETs
        if ($l.Count -gt $i -and ($l[$i][1]['b'] - $l[$i][1]['t']) -ge 100) { return $l[$i][0] }
        $sy = 0
        foreach ($y in 1400..1900) {
            $bad = $false
            foreach ($e in $l) { if ($y -ge $e[1]['t'] - 4 -and $y -le $e[1]['b'] + 4) { $bad = $true; break } }
            if (-not $bad) { $sy = $y; break }
        }
        if ($sy -eq 0) { $sy = 1500 }
        $ty = if ($dir -gt 0) { $sy - 100 } else { $sy + 100 }
        Say ("    scroll dir={0} {1}->{2} try{3}" -f $dir, $sy, $ty, $k)
        FastScroll 800 $sy $ty 80
        $null = Cap ("c4_x_{0}_{1}" -f $i, $k)
    }
    $l = FormETs
    if ($l.Count -gt $i -and ($l[$i][1]['b'] - $l[$i][1]['t']) -ge 100) { return $l[$i][0] }
    return $null
}

# find the form field whose text == $want (sentinel readback, IME-independent)
function FindETByText([string]$want) {
    foreach ($n in (DumpET)) {
        $t = TxtOf $n
        if ($t -eq $want -or ($t -and $t.Contains($want))) {
            $b = Bnd $n
            Say ("    SENTINEL '{0}' found at [{1},{2}][{3},{4}] @{5},{6}" -f $t, $b['l'],$b['t'],$b['r'],$b['b'], $b['cx'],$b['cy'])
            return $n
        }
    }
    Say ("    SENTINEL '{0}' NOT FOUND in any EditText" -f $want)
    return $null
}

function NewEntry([int]$idx, [string]$hstr, [string]$nmv) {
    Say ""
    Say "############ ENTRY $idx  name=$nmv ############"
    $null = Cap ("c4_e{0}_a_pre" -f $idx)
    $bs0 = BtnState "Add server"
    if ($bs0 -notmatch 'anc_bounds=\[723,1736\]') {
        Say "  form not open -> tap Add SSH server"
        $null = TapTxt "Add SSH server" 0
        $null = Cap ("c4_e{0}_b_form" -f $idx)
        $bs0 = BtnState "Add server"
    }
    Say ("  BTN_BEFORE {0}" -f $bs0)
    ShowFormETs ("e{0}_open" -f $idx)

    # ---- role0 = host ----
    $f = ExposeRole 0 -1
    if (-not ($f -is [System.Xml.XmlElement])) { Say "  HOST_NOT_FOUND"; ShowFormETs ("e{0}_fh" -f $idx); return $false }
    $b = Bnd $f
    Say ("  HOST [{0},{1}][{2},{3}] h={4} @{5},{6}" -f $b['l'],$b['t'],$b['r'],$b['b'], ($b['b']-$b['t']), $b['cx'],$b['cy'])
    TapXY $b['cx'] $b['cy']
    TypeS $hstr
    $null = DropIme ("e{0}_host" -f $idx)
    $null = FindETByText $hstr

    # ---- role1 = name ----
    $f2 = ExposeRole 1 -1
    if (-not ($f2 -is [System.Xml.XmlElement])) { Say "  NAME_NOT_FOUND"; ShowFormETs ("e{0}_fn" -f $idx); return $false }
    $b2 = Bnd $f2
    Say ("  NAME [{0},{1}][{2},{3}] h={4} @{5},{6}" -f $b2['l'],$b2['t'],$b2['r'],$b2['b'], ($b2['b']-$b2['t']), $b2['cx'],$b2['cy'])
    TapXY $b2['cx'] $b2['cy']
    TypeS $nmv
    $null = DropIme ("e{0}_name" -f $idx)
    $null = FindETByText $nmv
    $pc = FindPat "Password"
    Say ("  auth 'Password' nodes={0}" -f $pc.Count)

    $bs2 = BtnState "Add server"
    Say ("  BTN_AFTER {0}" -f $bs2)
    $null = Cap ("c4_e{0}_e_ready" -f $idx)
    if ($bs2 -notmatch 'anc_en=true') { Say "  *** ADD SERVER STILL DISABLED ***"; return $false }

    $anc = ClickAncestor (FindN "Add server" 0)
    $ab = Bnd $anc
    Say ("  tap Add server anc @{0},{1} [{2},{3}][{4},{5}]" -f $ab['cx'],$ab['cy'],$ab['l'],$ab['t'],$ab['r'],$ab['b'])
    TapXY $ab['cx'] $ab['cy']
    $null = Cap ("c4_e{0}_f_afteradd" -f $idx)
    $null = IsANR
    return $true
}

$HSTR = "ssh -p 2222 sshtest@127.0.0.1"
$res = @()
foreach ($i in 1..4) {
    $nm = "E2ECHK5554d3c-$i"
    $ok = NewEntry $i $HSTR $nm
    Say "ENTRY_${i}_OK=$ok"
    $res += $ok
    $null = Cap ("c4_e{0}_g_precard" -f $i)
    $hasCard = $false; $hasBar = $false
    foreach ($n in (Nodes)) {
        $t = TxtOf $n
        if ($t -like "*password for this connection*") { $hasCard = $true }
        if ($t -eq "Delete SSH server?") { $hasBar = $true }
    }
    Say ("  PASSWORD_CARD={0} CONFIRM_BAR={1}" -f $hasCard, $hasBar)
    if ($hasCard -and -not $hasBar) {
        $cn = FindN "Cancel" 0
        if ($cn -is [System.Xml.XmlElement]) {
            $cb = Bnd $cn
            Say ("  dismiss password card Cancel @{0},{1}" -f $cb['cx'],$cb['cy'])
            TapXY $cb['cx'] $cb['cy']
            $null = Cap ("c4_e{0}_h_cardclosed" -f $i)
        }
    }
    $null = Cap ("c4_e{0}_i_rows" -f $i)
    $found = @(); foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -like "*E2ECHK5554d3c*") { $found += $t } }
    Say ("  SELF_MARKERS n={0} : {1}" -f $found.Count, ($found -join ' ; '))
}
Say "=== BUILD DONE ok=[$($res -join ',')] ==="
$null = Cap "c4_eZ_final"
Say "---- FINAL ROWS ----"
$null = RowCensus "final"
