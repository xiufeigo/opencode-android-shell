. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir

# ★ RECIPE (found by experiment this round):
#   IME cannot be closed without BACK/ESC -> so we never close it.
#   Instead: tap host -> type -> `input keyevent 20` (DPAD_DOWN) moves focus to the
#   name field AND scrolls the form so it is fully visible -> type name (focused already,
#   no tap needed) -> tap Add server at LIVE bounds (it sits above the keyboard).
function Ime { $o = & $global:ADB -s emulator-5554 shell "dumpsys input_method | grep -E 'mInputShown' | head -1"; return ($o -join ' ').Trim() }

function FormETs {
    $sv = $null
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("class") -ne "android.widget.ScrollView") { continue }
        $b = Bnd $n; if (-not $b) { continue }
        $hit = $false
        foreach ($e in (DumpET)) { $eb = Bnd $e; if ($eb['t'] -ge $b['t'] - 2 -and $eb['b'] -le $b['b'] + 2) { $hit = $true; break } }
        if ($hit -and -not $sv) { $sv = $b }
    }
    if (-not $sv) { return @() }
    $l = @()
    foreach ($n in (DumpET)) { $b = Bnd $n
        if ($b['t'] -lt $sv['t'] - 2) { continue }
        if ($b['b'] -gt $sv['b'] + 2) { continue }
        $l += ,@($n, $b) }
    return @($l | Sort-Object { $_[1]['t'] })
}
# the focused form EditText
function FocusedET {
    foreach ($e in (FormETs)) { if ($e[0].GetAttribute("focused") -eq "true") { return $e } }
    return $null
}
function ETByText([string]$w) {
    foreach ($e in (FormETs)) { $t = TxtOf $e[0]; if ($t -and $t.Contains($w)) { return $e } }
    return $null
}

$HOSTV = "ssh -p 2222 sshtest@127.0.0.1"

function BuildEntry([int]$idx, [string]$nmv) {
    Say ""
    Say "############ ENTRY $idx  $nmv ############"
    $null = Cap ("c4_E{0}_a_pre" -f $idx)
    if (-not (FormETs)) {
        Say "  open form"
        $null = TapTxt "Add SSH server" 0
        $null = Cap ("c4_E{0}_b_open" -f $idx)
    }
    # ★ GUARANTEE A CLEAN FORM: if any field already has text, close+reopen.
    #   (round-3 verified: closing the form leaves composition -> remember state is discarded)
    $dirty = $false
    foreach ($e in (FormETs)) { if ((TxtOf $e[0])) { $dirty = $true } }
    if ($dirty) {
        Say "  form is DIRTY -> close+reopen for a clean form"
        $cn = FindN "Cancel" 0
        if ($cn) { $cb = Bnd $cn; Say ("    tap Cancel @{0},{1}" -f $cb['cx'],$cb['cy']); TapXY $cb['cx'] $cb['cy'] }
        $null = Cap ("c4_E{0}_c0_closed" -f $idx)
        $null = TapTxt "Add SSH server" 0
        $null = Cap ("c4_E{0}_c1_reopen" -f $idx)
        $still = 0
        foreach ($e in (FormETs)) { if ((TxtOf $e[0])) { $still++ } }
        Say ("    after reopen: non-empty fields = {0}" -f $still)
    }
    Say ("  BTN_BEFORE " + (BtnState "Add server"))

    # --- host: needs a real tap because it is not focused yet ---
    $l  = @(FormETs)
    if ($l.Count -lt 1) { Say "  HOST_NOT_VISIBLE"; return $false }
    $hb = $l[0][1]
    Say ("  host [{0},{1}][{2},{3}] old='{4}'" -f $hb['l'],$hb['t'],$hb['r'],$hb['b'],(TxtOf $l[0][0]))
    TapXY $hb['cx'] $hb['cy']
    TypeS $HOSTV
    $null = Cap ("c4_E{0}_c_host" -f $idx)
    $h = ETByText "sshtest@127.0.0.1"
    if ($h) { Say ("  SENTINEL host = '{0}' [{1},{2}][{3},{4}]" -f (TxtOf $h[0]),$h[1]['l'],$h[1]['t'],$h[1]['r'],$h[1]['b']) }
    else { Say "  SENTINEL host MISMATCH" }

    # --- FIRST scroll the name field into view (DPAD cannot focus a clipped field) ---
    for ($k = 0; $k -lt 6; $k++) {
        $l  = @(FormETs)
        if ($l.Count -ge 2 -and ($l[1][1]['b'] - $l[1][1]['t']) -ge 100) { Say ("    name visible after {0} scroll(s): [{1},{2}][{3},{4}]" -f $k,$l[1][1]['l'],$l[1][1]['t'],$l[1][1]['r'],$l[1][1]['b']); break }
        $sv = $null
        foreach ($n in (Nodes)) { if ($n.GetAttribute("class") -ne "android.widget.ScrollView") { continue }
            $b = Bnd $n; if (-not $b) { continue }
            foreach ($e in (DumpET)) { $eb = Bnd $e; if ($eb['t'] -ge $b['t'] - 2 -and $eb['b'] -le $b['b'] + 2) { $sv = $b; break } }
            if ($sv) { break } }
        if (-not $sv) { Say "    no form SV to scroll"; break }
        $cy = [int](($sv['t'] + $sv['b']) / 2)
        Say ("    scroll form up {0}: swipe 800 {1}->{2}" -f $k, $cy, ($cy - 100))
        FastScroll 800 $cy ($cy - 100) 80
        $null = Cap ("c4_E{0}_dsc{1}" -f $idx, $k)
    }
    # --- DPAD_DOWN -> focus lands on the name field ---
    Sh "input keyevent 20"; Start-Sleep -Milliseconds 900
    $null = Cap ("c4_E{0}_d_dpad" -f $idx)
    $fo = FocusedET
    if (-not $fo) { Say "  NO_FOCUSED_FIELD_AFTER_DPAD"; foreach ($e in (FormETs)) { $b=$e[1]; Say ("    ET [{0},{1}][{2},{3}] foc={4} '{5}'" -f $b['l'],$b['t'],$b['r'],$b['b'],$e[0].GetAttribute("focused"),(TxtOf $e[0])) } ; return $false }
    $fb = $fo[1]
    Say ("  focused after DPAD [{0},{1}][{2},{3}] h={4} text='{5}'" -f $fb['l'],$fb['t'],$fb['r'],$fb['b'],($fb['b']-$fb['t']),(TxtOf $fo[0]))

    # --- type the name into the focused field (no tap) ---
    TypeS $nmv
    $null = Cap ("c4_E{0}_e_name" -f $idx)
    $n = ETByText $nmv
    if ($n) { Say ("  SENTINEL name = '{0}' [{1},{2}][{3},{4}]" -f (TxtOf $n[0]),$n[1]['l'],$n[1]['t'],$n[1]['r'],$n[1]['b']) }
    else { Say "  SENTINEL name MISMATCH"; foreach ($e in (FormETs)) { $b=$e[1]; Say ("    ET [{0},{1}][{2},{3}] foc={4} '{5}'" -f $b['l'],$b['t'],$b['r'],$b['b'],$e[0].GetAttribute("focused"),(TxtOf $e[0])) } }
    $pc = FindPat "Password"; Say ("  auth 'Password' nodes={0}" -f $pc.Count)
    Say ("  BTN_AFTER " + (BtnState "Add server"))
    Say ("  IME=" + (Ime))
    $null = Cap ("c4_E{0}_f_ready" -f $idx)

    $anc = ClickAncestor (FindN "Add server" 0)
    $ab = Bnd $anc
    Say ("  TAP Add server anc @{0},{1} [{2},{3}][{4},{5}]" -f $ab['cx'],$ab['cy'],$ab['l'],$ab['t'],$ab['r'],$ab['b'])
    TapXY $ab['cx'] $ab['cy']
    $null = Cap ("c4_E{0}_g_after" -f $idx)
    $null = IsANR

    # --- dismiss the row-level password card (form is gone by now, so Cancel#0 is the card's) ---
    $hasCard = $false
    foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -like "*password for this connection*") { $hasCard = $true } }
    Say ("  PASSWORD_CARD={0}  FORM_STILL_OPEN={1}" -f $hasCard, [bool](FormETs))
    if ($hasCard) {
        $cn = FindN "Cancel" 0
        if ($cn) { $cb = Bnd $cn; Say ("  dismiss card Cancel @{0},{1}" -f $cb['cx'],$cb['cy']); TapXY $cb['cx'] $cb['cy']; $null = Cap ("c4_E{0}_h_card" -f $idx) }
    }
    $null = Cap ("c4_E{0}_i_rows" -f $idx)
    $found = @(); foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -like "*E2ECHK5554d3c*") { $found += $t } }
    Say ("  SELF_MARKERS n={0} : {1}" -f $found.Count, ($found -join ' ; '))
    return ($found.Count -gt 0)
}

$r = @()
foreach ($i in 1..4) { $ok = BuildEntry $i "E2ECHK5554d3c-$i"; Say "ENTRY_${i}_OK=$ok"; $r += $ok }
Say "=== DONE ok=[$($r -join ',')] ==="
$null = Cap "c4_EZ_final"
Say "---- FINAL LIST ----"
foreach ($n in (Nodes)) { $b = Bnd $n; if (-not $b) { continue }
    if ($b['t'] -lt 800) { continue }
    $t = TxtOf $n; if (-not $t) { continue }
    Say ("  [{0},{1}][{2},{3}] {5} | {4}" -f $b['l'],$b['t'],$b['r'],$b['b'],$t,$n.GetAttribute("class")) }



