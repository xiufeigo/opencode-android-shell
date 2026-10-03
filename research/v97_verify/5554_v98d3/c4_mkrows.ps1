. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir

function FormETs2 {
    $sv = $null
    foreach ($n in (Nodes)) {
        if ($n.GetAttribute("class") -ne "android.widget.ScrollView") { continue }
        $b = Bnd $n; if (-not $b) { continue }
        foreach ($e in (DumpET)) { $eb = Bnd $e; if ($eb['t'] -ge $b['t'] - 2 -and $eb['b'] -le $b['b'] + 2) { $sv = $b; break } }
        if ($sv) { break }
    }
    if (-not $sv) { return @() }
    $l = @()
    foreach ($n in (DumpET)) { $b = Bnd $n
        if ($b['t'] -lt $sv['t'] - 2) { continue }
        if ($b['b'] -gt $sv['b'] + 2) { continue }
        $l += ,@($n, $b) }
    return @($l | Sort-Object { $_[1]['t'] })
}
function Wipe([int]$n = 120) {
    Sh "input keyevent 123"
    Sh ('i=0; while [ $i -lt ' + $n + ' ]; do input keyevent 67; i=$((i+1)); done')
    Start-Sleep -Milliseconds 250
}
function SelfTitles {
    $o = @()
    foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -like "*E2ECHK5554d3c*") { $b = Bnd $n; $o += ,@($t, $b) } }
    return @($o | Sort-Object { $_[1]['t'] })
}
function KebabList {
    $l = @(); foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Server actions") { $l += ,@(Bnd $n) } }
    return @($l | Sort-Object { $_['t'] })
}
function ShowRows([string]$tag) {
    Say "  ---- rows [$tag] ----"
    foreach ($e in (SelfTitles)) { Say ("    SELF [{0},{1}][{2},{3}] | {4}" -f $e[1]['l'],$e[1]['t'],$e[1]['r'],$e[1]['b'],$e[0]) }
    $k = 0; foreach ($e in (KebabList)) { Say ("    kebab#{0} [{1},{2}][{3},{4}] @{5},{6}" -f $k,$e['l'],$e['t'],$e['r'],$e['b'],$e['cx'],$e['cy']); $k++ }
    Say ("    SELF_COUNT={0} KEBAB_COUNT={1}" -f (SelfTitles).Count, (KebabList).Count)
}

# minimal, PROVEN row factory: one field, host text carries the unique tag
function MakeRow([int]$idx) {
    $tag = "E2ECHK5554d3c-$idx"
    Say ""
    Say "###### MAKE ROW $tag ######"
    $null = Cap ("c4_M{0}_a_pre" -f $idx)
    if (-not (FormETs2)) {
        Say "  open form"
        $null = TapTxt "Add SSH server" 0
        $null = Cap ("c4_M{0}_b_open" -f $idx)
    }
    $l = @(FormETs2)
    if ($l.Count -lt 1) { Say "  NO_FIELD_VISIBLE"; return $false }
    $b = $l[0][1]
    Say ("  field0 [{0},{1}][{2},{3}] old='{4}'" -f $b['l'],$b['t'],$b['r'],$b['b'],(TxtOf $l[0][0]))
    TapXY $b['cx'] $b['cy']
    Wipe 120
    # host text with the unique tag baked in -> the row title (hostname fallback) is unique
    TypeS "ssh -p 2222 sshtest@127.0.0.1$tag"
    Start-Sleep -Milliseconds 600
    $null = Cap ("c4_M{0}_c_typed" -f $idx)
    $hit = $false
    foreach ($e in (FormETs2)) { if ((TxtOf $e[0]) -like "*$tag*") { $hit = $true; Say ("  SENTINEL field0 = '{0}'" -f (TxtOf $e[0])) } }
    if (-not $hit) { Say "  TYPED_TEXT_NOT_FOUND" }
    Say ("  BTN " + (BtnState "Add server"))
    $null = Cap ("c4_M{0}_d_ready" -f $idx)
    $an = FindN "Add server" 0
    if (-not $an) { Say "  NO_ADD_SERVER_NODE"; return $false }
    $anc = ClickAncestor $an
    if (-not $anc) { Say "  NO_CLICKABLE_ANCESTOR"; return $false }
    $ab = Bnd $anc
    Say ("  TAP Add server @{0},{1}" -f $ab['cx'],$ab['cy'])
    TapXY $ab['cx'] $ab['cy']
    $null = Cap ("c4_M{0}_e_after" -f $idx)
    $null = IsANR
    $hasCard = $false
    foreach ($n in (Nodes)) { if ((TxtOf $n) -like "*password for this connection*") { $hasCard = $true } }
    Say ("  PASSWORD_CARD={0}" -f $hasCard)
    if ($hasCard) {
        $cn = FindN "Cancel" 0
        if ($cn) { $cb = Bnd $cn; Say ("  dismiss Cancel @{0},{1}" -f $cb['cx'],$cb['cy']); TapXY $cb['cx'] $cb['cy']; Start-Sleep -Milliseconds 500; $null = Cap ("c4_M{0}_f_card" -f $idx) }
    }
    $null = Cap ("c4_M{0}_g_rows" -f $idx)
    ShowRows ("after_make_{0}" -f $idx)
    $found = $false
    foreach ($e in (SelfTitles)) { if ($e[0] -like "*$tag*") { $found = $true } }
    return $found
}

$before = (SelfTitles).Count
Say ("BASELINE SELF_ROWS={0}" -f $before)
foreach ($i in 2..4) { $ok = MakeRow $i; Say "MAKE_ROW_${i}_OK=$ok" }
Say ("FINAL SELF_ROWS={0}" -f (SelfTitles).Count)
$null = Cap "c4_MZ_final"
ShowRows "final"
