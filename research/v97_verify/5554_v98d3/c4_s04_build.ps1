. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir

# map a form field by its LABEL: the first full-height EditText below the label
function FieldForLabel([string]$label) {
    $ln = FindN $label 0
    if (-not ($ln -is [System.Xml.XmlElement])) { return $null }
    $lb = Bnd $ln
    $best = $null; $bd = 99999
    foreach ($n in (DumpET)) {
        $b = Bnd $n
        if ($b['b'] -lt $lb['b'] - 5) { continue }
        if ($b['t'] -lt $lb['b'] - 5) { continue }          # must start below the label
        if ($b['b'] -gt $lb['b'] + 260) { continue }      # within one field pitch
        $d = $b['t'] - $lb['b']
        if ($d -lt $bd) { $bd = $d; $best = $n }
    }
    return $best
}

function ETHeight($n) { $b = Bnd $n; return ($b['b'] - $b['t']) }

function ImeShown {
    $o = & $global:ADB -s emulator-5554 shell "dumpsys input_method | grep -E 'mInputShown|mVisible' | head -3"
    return ($o -join ' | ')
}

# find a full-height field for a label, scrolling the form if needed
function EnsureField([string]$label, [int]$tries = 4) {
    for ($i = 0; $i -lt $tries; $i++) {
        $f = FieldForLabel $label
        if ($f -is [System.Xml.XmlElement] -and (ETHeight $f) -ge 100) { return $f }
        # scroll: pick a y inside the form card that is not on an EditText
        $ets = @(); foreach ($n in (DumpET)) { $b = Bnd $n; $ets += ,@($b['t'], $b['b']) }
        $sy = 0
        foreach ($y in 1400..1900) {
            $bad = $false
            foreach ($r in $ets) { if ($y -ge $r[0] - 4 -and $y -le $r[1] + 4) { $bad = $true; break } }
            if (-not $bad) { $sy = $y; break }
        }
        if ($sy -eq 0) { $sy = 1500 }
        Say ("    scroll to expose '{0}' at y={1} (try {2})" -f $label, $sy, $i)
        FastScroll 800 $sy ($sy - 100) 80
        $null = Cap ("c4_sc_{0}_{1}" -f ($label -replace '[^A-Za-z]', ''), $i)
    }
    $f = FieldForLabel $label
    if ($f -is [System.Xml.XmlElement] -and (ETHeight $f) -ge 100) { return $f }
    return $null
}

function NewEntry([int]$idx, [string]$hstr, [string]$nmv) {
    Say ""
    Say "================ ENTRY $idx  name=$nmv ================"
    # open form
    $null = Cap ("c4_e{0}_a_pre" -f $idx)
    if (-not (FormOpen)) {
        $null = TapTxt "Add SSH server" 0
        $null = Cap ("c4_e{0}_b_form" -f $idx)
    }
    Say ("  FORM_OPEN=" + (FormOpen))
    $bs = BtnState "Add server"
    Say ("  BTN_BEFORE " + $bs)

    # --- host ---
    $f = EnsureField "Host or SSH command"
    if (-not ($f -is [System.Xml.XmlElement])) { Say "  HOST_FIELD_NOT_FOUND"; return $false }
    $b = Bnd $f
    Say ("  host field [{0},{1}][{2},{3}] h={4} @{5},{6}" -f $b['l'],$b['t'],$b['r'],$b['b'], (ETHeight $f), $b['cx'],$b['cy'])
    TapXY $b['cx'] $b['cy']
    TypeS $hstr
    $null = Cap ("c4_e{0}_c_host_typed" -f $idx)
    # sentinel readback
    $hf = FieldForLabel "Host or SSH command"
    if ($hf -is [System.Xml.XmlElement]) {
        $hb = Bnd $hf
        Say ("  SENTINEL host readback text='{0}' bounds=[{1},{2}][{3},{4}]" -f (TxtOf $hf), $hb['l'],$hb['t'],$hb['r'],$hb['b'])
    } else { Say "  SENTINEL host NOT_FOUND" }

    # --- name ---
    $f2 = EnsureField "Server name (optional)"
    if (-not ($f2 -is [System.Xml.XmlElement])) { Say "  NAME_FIELD_NOT_FOUND"; return $false }
    $b2 = Bnd $f2
    Say ("  name field [{0},{1}][{2},{3}] h={4} @{5},{6}" -f $b2['l'],$b2['t'],$b2['r'],$b2['b'], (ETHeight $f2), $b2['cx'],$b2['cy'])
    TapXY $b2['cx'] $b2['cy']
    TypeS $nmv
    $null = Cap ("c4_e{0}_d_name_typed" -f $idx)
    $nf = FieldForLabel "Server name (optional)"
    if ($nf -is [System.Xml.XmlElement]) {
        $nb = Bnd $nf
        Say ("  SENTINEL name readback text='{0}' bounds=[{1},{2}][{3},{4}]" -f (TxtOf $nf), $nb['l'],$nb['t'],$nb['r'],$nb['b'])
    } else { Say "  SENTINEL name NOT_FOUND" }

    # --- auth selector default ---
    $auth = FindPat "Password"
    Say ("  auth 'Password' nodes = " + $auth.Count)

    # --- dismiss IME by tapping the form card title (NOT BACK, NOT ESC) ---
    $tn = FindN "Add SSH server" 0
    $tb = $null
    if ($tn -is [System.Xml.XmlElement]) {
        foreach ($c in (Nodes)) { $cc = Bnd $c; $cb = Bnd $tn
            if ($cc['t'] -ge $cb['t'] - 30 -and $cc['b'] -le $cb['b'] + 30) { $tb = $cc; break } }
    }
    if ($tb) { Say ("  tap neutral y={0} to drop IME" -f $tb['cy']); TapXY 700 $tb['cy'] }
    $null = Cap ("c4_e{0}_e_ime_drop" -f $idx)
    Say ("  IME: " + (ImeShown))

    # --- assert Add server is now enabled ---
    $bs2 = BtnState "Add server"
    Say ("  BTN_AFTER " + $bs2)
    if ($bs2 -notmatch 'anc_en=true') { Say "  *** ADD SERVER STILL DISABLED ***"; return $false }

    # --- tap it (use the clickable ancestor center) ---
    $an = FindN "Add server" 0
    $anc = ClickAncestor $an
    $ab = Bnd $anc
    Say ("  tap Add server ancestor @{0},{1}" -f $ab['cx'],$ab['cy'])
    TapXY $ab['cx'] $ab['cy']
    $null = Cap ("c4_e{0}_f_after_add" -f $idx)

    # --- what happened? ---
    Say ("  FORM_STILL_OPEN=" + (FormOpen))
    $null = RowCensus ("e{0}_after_add" -f $idx)
    $x = $null
    $null = IsANR
    return $true
}

$HSTR = "ssh -p 2222 sshtest@127.0.0.1"
foreach ($i in 1..4) {
    $nm = "E2ECHK5554d3c-$i"
    $ok = NewEntry $i $HSTR $nm
    Say "ENTRY_${i}_SUBMITTED_OK=$ok"
    # dismiss any row-level password card with its own Cancel (safe: no confirm bar armed)
    $null = Cap ("c4_e{0}_g_precard" -f $i)
    $hasCard = $false
    foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -like "*password for this connection*") { $hasCard = $true } }
    $hasBar = $false
    foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -eq "Delete SSH server?") { $hasBar = $true } }
    Say ("  PASSWORD_CARD={0} CONFIRM_BAR={1}" -f $hasCard, $hasBar)
    if ($hasCard -and -not $hasBar) {
        $cn = FindN "Cancel" 0
        if ($cn -is [System.Xml.XmlElement]) {
            $cb = Bnd $cn
            Say ("  dismiss password card via Cancel @{0},{1}" -f $cb['cx'],$cb['cy'])
            TapXY $cb['cx'] $cb['cy']
            $null = Cap ("c4_e{0}_h_card_dismissed" -f $i)
        }
    }
    # row census after this entry
    Say ("  --- rows after entry $i ---")
    $null = Cap ("c4_e{0}_i_rows" -f $i)
    $found = 0
    foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -like "*E2ECHK5554d3c*") { $found++ } }
    Say ("  SELF_ROWS_MARKERS={0}" -f $found)
}
Say "=== LOOP DONE ==="

