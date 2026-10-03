. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir

# ---------- deterministic form-field helpers ----------
function FormSV {
    $sv = $null
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("class") -ne "android.widget.ScrollView") { continue }
        $b = Bnd $n; if (-not $b) { continue }
        foreach ($e in (DumpET)) { $eb = Bnd $e; if ($eb['t'] -ge $b['t'] - 2 -and $eb['b'] -le $b['b'] + 2) { $sv = $b; break } }
        if ($sv) { break }
    }
    return $sv
}
# all form EditTexts inside the form ScrollView, sorted by y
function FormETs2 {
    $sv = FormSV; if (-not $sv) { return @() }
    $l = @()
    foreach ($n in (DumpET)) { $b = Bnd $n
        if ($b['t'] -lt $sv['t'] - 2) { continue }
        if ($b['b'] -gt $sv['b'] + 2) { continue }
        $l += ,@($n, $b) }
    return @($l | Sort-Object { $_[1]['t'] })
}
# the EditText nearest to a given y (used for the +200 offset rule)
function ETNear([int]$y, [int]$tol = 30) {
    $best = $null; $bd = 99999
    foreach ($e in (FormETs2)) {
        $d = [math]::Abs($e[1]['cy'] - $y)
        if ($d -le $tol -and $d -lt $bd) { $bd = $d; $best = $e }
    }
    return $best
}
# RELIABLE clear: MOVE_END then N DELs (deletes backwards from the tail)
function Wipe([int]$n = 120) {
    Sh "input keyevent 123"
    Sh ('i=0; while [ $i -lt ' + $n + ' ]; do input keyevent 67; i=$((i+1)); done')
    Start-Sleep -Milliseconds 250
}
function ETTextAt([string]$w) {
    foreach ($e in (FormETs2)) { $t = TxtOf $e[0]; if ($t -and $t.Contains($w)) { return $e } }
    return $null
}
function ShowF([string]$tag) {
    $sv = FormSV
    if ($sv) { Say ("  FORM_SV=[{0},{1}][{2},{3}]" -f $sv['l'],$sv['t'],$sv['r'],$sv['b']) } else { Say "  FORM_SV=NONE" }
    foreach ($e in (FormETs2)) { $b = $e[1]
        Say ("    ET [{0},{1}][{2},{3}] h={4} foc={5} | '{6}'" -f $b['l'],$b['t'],$b['r'],$b['b'],($b['b']-$b['t']),$e[0].GetAttribute("focused"),(TxtOf $e[0])) }
}

$HOSTV = "ssh -p 2222 sshtest@127.0.0.1"
$OFF   = 200   # ★ name field sits exactly 200px below the host field in every reading

# ---------- make sure a CLEAN form is open ----------
function CleanForm {
    for ($k = 0; $k -lt 4; $k++) {
        $sv = FormSV
        if (-not $sv) {
            Say "  [clean] no form -> open"
            $null = TapTxt "Add SSH server" 0
            $null = Cap ("c4_C_b_open{0}" -f $k)
            continue
        }
        # wipe every visible form field in place
        $dirty = $false
        foreach ($e in (FormETs2)) { if ((TxtOf $e[0])) { $dirty = $true } }
        if (-not $dirty) { Say "  [clean] form is clean"; return $true }
        Say "  [clean] wiping dirty fields in place"
        foreach ($e in (FormETs2)) {
            if (-not (TxtOf $e[0])) { continue }
            $b = $e[1]
            Say ("    wipe [{0},{1}][{2},{3}]" -f $b['l'],$b['t'],$b['r'],$b['b'])
            TapXY $b['cx'] $b['cy']; Wipe 120
        }
        $null = Cap ("c4_C_c_wiped{0}" -f $k)
        $still = 0
        foreach ($e in (FormETs2)) { if ((TxtOf $e[0])) { $still++ } }
        if ($still -eq 0) { Say "  [clean] all fields wiped"; return $true }
    }
    return $false
}

function BuildEntry([int]$idx, [string]$nmv) {
    Say ""
    Say "############ ENTRY $idx  $nmv ############"
    $null = Cap ("c4_E{0}_a_pre" -f $idx)
    if (-not (CleanForm)) { Say "  CLEAN_FORM_FAILED"; return $false }
    $null = Cap ("c4_E{0}_b_clean" -f $idx)
    Say ("  BTN_BEFORE " + (BtnState "Add server"))

    # ---- host ----
    $l = @(FormETs2)
    if ($l.Count -lt 1) { Say "  HOST_NOT_VISIBLE"; return $false }
    $hb = $l[0][1]
    Say ("  host [{0},{1}][{2},{3}] cy={4} old='{5}'" -f $hb['l'],$hb['t'],$hb['r'],$hb['b'],$hb['cy'],(TxtOf $l[0][0]))
    TapXY $hb['cx'] $hb['cy']; Wipe 120
    TypeS $HOSTV
    Start-Sleep -Milliseconds 500
    $null = Cap ("c4_E{0}_c_host" -f $idx)
    $h = ETTextAt "sshtest@127.0.0.1"
    if ($h) { Say ("  SENTINEL host = '{0}' [{1},{2}][{3},{4}]" -f (TxtOf $h[0]),$h[1]['l'],$h[1]['t'],$h[1]['r'],$h[1]['b']) }
    else { Say "  SENTINEL host MISMATCH"; ShowF ("e{0}_hm" -f $idx); return $false }

    # ---- name at host_cy + 200 : scroll until that position holds a full-height field ----
    $target = 0
    for ($k = 0; $k -lt 6; $k++) {
        $h2 = ETTextAt "sshtest@127.0.0.1"
        if (-not $h2) { Say "  HOST_LOST"; return $false }
        $ny = $h2[1]['cy'] + $OFF
        $e2 = ETNear $ny 34
        if ($e2 -and ($e2[1]['b'] - $e2[1]['t']) -ge 100) { $target = $ny; Say ("  name slot found at y={0} after {1} scroll(s) bounds=[{2},{3}][{4},{5}]" -f $ny,$k,$e2[1]['l'],$e2[1]['t'],$e2[1]['r'],$e2[1]['b']); break }
        $sv = FormSV; if (-not $sv) { break }
        $cy = [int](($sv['t'] + $sv['b']) / 2)
        Say ("    scroll form up {0}: swipe 800 {1}->{2}" -f $k, $cy, ($cy - 100))
        FastScroll 800 $cy ($cy - 100) 80
        $null = Cap ("c4_E{0}_d_sc{1}" -f $idx, $k)
    }
    if ($target -eq 0) { Say "  NAME_SLOT_NOT_FOUND"; ShowF ("e{0}_ns" -f $idx); return $false }

    $h3 = ETTextAt "sshtest@127.0.0.1"
    $ny = $h3[1]['cy'] + $OFF
    Say ("  tap name slot @{0},{1}" -f 540, $ny)
    TapXY 540 $ny
    $null = Cap ("c4_E{0}_e_nametap" -f $idx)
    $fo = $null
    foreach ($e in (FormETs2)) { if ($e[0].GetAttribute("focused") -eq "true") { $fo = $e } }
    if ($fo) { Say ("  focused field [{0},{1}][{2},{3}]" -f $fo[1]['l'],$fo[1]['t'],$fo[1]['r'],$fo[1]['b']) }
    else { Say "  no focused field after name tap" }
    Wipe 120
    TypeS $nmv
    Start-Sleep -Milliseconds 500
    $null = Cap ("c4_E{0}_f_name" -f $idx)
    $n = ETTextAt $nmv
    if ($n) { Say ("  SENTINEL name = '{0}' [{1},{2}][{3},{4}]" -f (TxtOf $n[0]),$n[1]['l'],$n[1]['t'],$n[1]['r'],$n[1]['b']) }
    else { Say "  SENTINEL name MISMATCH"; ShowF ("e{0}_nm" -f $idx); return $false }
    $pc = FindPat "Password"; Say ("  auth 'Password' nodes={0}" -f $pc.Count)
    $hb2 = ETTextAt "sshtest@127.0.0.1"
    Say ("  HOST_UNCHANGED = '{0}'" -f (TxtOf $hb2[0]))
    Say ("  BTN_AFTER " + (BtnState "Add server"))
    $null = Cap ("c4_E{0}_g_ready" -f $idx)

    $anc = ClickAncestor (FindN "Add server" 0)
    $ab = Bnd $anc
    Say ("  TAP Add server anc @{0},{1}" -f $ab['cx'],$ab['cy'])
    TapXY $ab['cx'] $ab['cy']
    $null = Cap ("c4_E{0}_h_after" -f $idx)
    $null = IsANR
    $hasCard = $false
    foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -like "*password for this connection*") { $hasCard = $true } }
    Say ("  PASSWORD_CARD={0}" -f $hasCard)
    if ($hasCard) {
        $cn = FindN "Cancel" 0
        if ($cn) { $cb = Bnd $cn; Say ("  dismiss card Cancel @{0},{1}" -f $cb['cx'],$cb['cy']); TapXY $cb['cx'] $cb['cy']; $null = Cap ("c4_E{0}_i_card" -f $idx) }
    }
    $null = Cap ("c4_E{0}_j_rows" -f $idx)
    $found = @(); foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -like "*E2ECHK5554d3c*") { $found += $t } }
    Say ("  SELF_MARKERS n={0} : {1}" -f $found.Count, ($found -join ' ; '))
    return ($found.Count -gt 0)
}

$r = @()
foreach ($i in 1..4) { $ok = BuildEntry $i "E2ECHK5554d3c-$i"; Say "ENTRY_${i}_OK=$ok"; $r += $ok }
Say "=== DONE ok=[$($r -join ',')] ==="
$null = Cap "c4_EZ_final"
Say "---- FINAL LIST y>800 ----"
foreach ($n in (Nodes)) { $b = Bnd $n; if (-not $b) { continue }
    if ($b['t'] -lt 800) { continue }
    $t = TxtOf $n; if (-not $t) { continue }
    Say ("  [{0},{1}][{2},{3}] {5} | {4}" -f $b['l'],$b['t'],$b['r'],$b['b'],$t,$n.GetAttribute("class")) }
