. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir

# ★ ROBUST ANCHOR: the form's OWN nested ScrollView (the one that contains the EditTexts).
# Immune to panel scroll offset, which is what broke the absolute y-window.
function FormSV {
    $best = $null
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("class") -ne "android.widget.ScrollView") { continue }
        $b = Bnd $n
        if (-not $b) { continue }
        # a form ScrollView must vertically span an EditText
        foreach ($e in (DumpET)) { $eb = Bnd $e
            if ($eb['t'] -ge $b['t'] - 2 -and $eb['b'] -le $b['b'] + 2) { return $b } }
    }
    return $best
}

function FormETs {
    $sv = FormSV
    if (-not $sv) { return @() }
    $list = @()
    foreach ($n in (DumpET)) {
        $b = Bnd $n
        if ($b['t'] -lt $sv['t'] - 2) { continue }
        if ($b['b'] -gt $sv['b'] + 2) { continue }
        $list += ,@($n, $b)
    }
    return @($list | Sort-Object { $_[1]['t'] })
}
function RoleName([int]$i) { if ($i -eq 0) { "Host or SSH command" } elseif ($i -eq 1) { "Server name (optional)" } else { "Password" } }

function ShowAll([string]$tag) {
    $sv = FormSV
    if ($sv) { Say ("  FORM_SCROLLVIEW=[{0},{1}][{2},{3}]" -f $sv['l'],$sv['t'],$sv['r'],$sv['b']) } else { Say "  FORM_SCROLLVIEW=NOT_FOUND" }
    $l = FormETs
    Say "  ---- form ETs [$tag] n=$($l.Count) ----"
    for ($i = 0; $i -lt $l.Count; $i++) {
        $b = $l[$i][1]
        Say ("    role{0} '{1}' [{2},{3}][{4},{5}] h={6} @{7},{8} text='{9}'" -f `
            $i, (RoleName $i), $b['l'],$b['t'],$b['r'],$b['b'], ($b['b']-$b['t']), $b['cx'],$b['cy'], (TxtOf $l[$i][0]))
    }
}

# scroll the FORM (swipe inside the form ScrollView), dir +1 = later content, -1 = earlier
function ScrollForm([int]$dir, [int]$px = 100, [int]$dur = 80) {
    $sv = FormSV
    if (-not $sv) { Say "    no form ScrollView -> skip scroll"; return }
    $cy = [int](($sv['t'] + $sv['b']) / 2)
    $x = 800
    $ty = if ($dir -gt 0) { $cy - $px } else { $cy + $px }
    FastScroll $x $cy $ty $dur
}

function ExposeRole([int]$i, [int]$tries = 6) {
    for ($k = 0; $k -lt $tries; $k++) {
        $l = FormETs
        if ($l.Count -gt $i -and ($l[$i][1]['b'] - $l[$i][1]['t']) -ge 100) { return $l[$i][0] }
        Say ("    scroll up (dir=-1) try {0}" -f $k)
        ScrollForm -1
        $null = Cap ("c4_sf_{0}_{1}" -f $i, $k)
    }
    $l = FormETs
    if ($l.Count -gt $i -and ($l[$i][1]['b'] - $l[$i][1]['t']) -ge 100) { return $l[$i][0] }
    return $null
}

function ImeShown {
    $o = & $global:ADB -s emulator-5554 shell "dumpsys input_method | grep -E 'mInputShown' | head -1"
    return ($o -join ' ').Trim()
}
function DropIme([string]$tag) {
    # x=995 is right of the Add server container [723,975] -> safe, never hits a button
    TapXY 995 ($([int](1420)))
    $null = Cap ("c4_ime_{0}" -f $tag)
    Say ("  IME[{0}]={1}" -f $tag, (ImeShown))
}
function FindETByText([string]$want) {
    foreach ($n in (DumpET)) {
        $t = TxtOf $n
        if ($t -and $t.Contains($want)) { $b = Bnd $n
            Say ("    SENTINEL '{0}' @[{1},{2}][{3},{4}]" -f $t,$b['l'],$b['t'],$b['r'],$b['b']); return $n } }
    Say ("    SENTINEL '{0}' NOT FOUND" -f $want); return $null
}

$STATE = @{ }

function NewEntry([int]$idx, [string]$hstr, [string]$nmv) {
    Say ""
    Say "############ ENTRY $idx  name=$nmv ############"
    $null = Cap ("c4_e{0}_a_pre" -f $idx)
    $sv = FormSV
    if (-not $sv) {
        Say "  form not open -> tap Add SSH server"
        $null = TapTxt "Add SSH server" 0
        $null = Cap ("c4_e{0}_b_open" -f $idx)
    }
    ShowAll ("e{0}_open" -f $idx)
    $bs0 = BtnState "Add server"
    Say ("  BTN_BEFORE {0}" -f $bs0)

    # ---- role0 host ----
    $f = ExposeRole 0
    if (-not ($f -is [System.Xml.XmlElement])) { Say "  HOST_NOT_FOUND"; ShowAll ("e{0}_fh" -f $idx); return $false }
    $cur = TxtOf $f
    if ($cur -eq $hstr) {
        Say "  HOST already correct -> skip typing"
    } else {
        $b = Bnd $f
        Say ("  HOST [{0},{1}][{2},{3}] @{4},{5} oldtext='{6}'" -f $b['l'],$b['t'],$b['r'],$b['b'],$b['cx'],$b['cy'],$cur)
        TapXY $b['cx'] $b['cy']
        if ((TxtOf $f)) { ClearET 60 }
        TypeS $hstr
        DropIme ("e{0}_host" -f $idx)
    }
    $null = FindETByText $hstr

    # ---- role1 name ----
    $f2 = ExposeRole 1
    if (-not ($f2 -is [System.Xml.XmlElement])) { Say "  NAME_NOT_FOUND"; ShowAll ("e{0}_fn" -f $idx); return $false }
    $b2 = Bnd $f2
    Say ("  NAME [{0},{1}][{2},{3}] @{4},{5} oldtext='{6}'" -f $b2['l'],$b2['t'],$b2['r'],$b2['b'],$b2['cx'],$b2['cy'],(TxtOf $f2))
    TapXY $b2['cx'] $b2['cy']
    if ((TxtOf $f2)) { ClearET 60 }
    TypeS $nmv
    DropIme ("e{0}_name" -f $idx)
    $null = FindETByText $nmv
    $pc = FindPat "Password"
    Say ("  auth 'Password' nodes={0}" -f $pc.Count)
    ShowAll ("e{0}_ready" -f $idx)

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
        if ($cn -is [System.Xml.XmlElement]) { $cb = Bnd $cn
            Say ("  dismiss password card Cancel @{0},{1}" -f $cb['cx'],$cb['cy'])
            TapXY $cb['cx'] $cb['cy']
            $null = Cap ("c4_e{0}_h_cardclosed" -f $i) }
    }
    $null = Cap ("c4_e{0}_i_rows" -f $i)
    $found = @(); foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -like "*E2ECHK5554d3c*") { $found += $t } }
    Say ("  SELF_MARKERS n={0} : {1}" -f $found.Count, ($found -join ' ; '))
}
Say "=== BUILD DONE ok=[$($res -join ',')] ==="
$null = Cap "c4_eZ_final"
Say "---- FINAL ----"
Say ("BTN " + (BtnState "Add server"))
Say "---- ALL y>400 ----"
foreach ($n in (Nodes)) {
  $b = Bnd $n; if (-not $b) { continue }
  if ($b['t'] -lt 400) { continue }
  $t = TxtOf $n
  if (-not $t) { continue }
  Say ("  [{0},{1}][{2},{3}] | {4}" -f $b['l'],$b['t'],$b['r'],$b['b'],$t)
}

