. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4_d_lib.ps1"
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
        $l += [PSCustomObject]@{ N = $n; B = $b } }
    return @($l | Sort-Object { $_.B['t'] })
}
function Wipe([int]$n = 120) {
    Sh "input keyevent 123"
    Sh ('i=0; while [ $i -lt ' + $n + ' ]; do input keyevent 67; i=$((i+1)); done')
    Start-Sleep -Milliseconds 250
}

# ★ distinct hostname per row -> the store does NOT dedupe these
function MakeRowHost([int]$idx, [string]$hostIp) {
    $tag = "E2ECHK5554d3c-$idx"
    Say ""
    Say "###### ROW $tag  host=$hostIp ######"
    $null = Cap ("c4_H{0}_a" -f $idx)
    if (-not (FormETs2)) { $null = TapTxt "Add SSH server" 0; $null = Cap ("c4_H{0}_b" -f $idx) }
    $l = @(FormETs2)
    if ($l.Count -lt 1) { Say "  NO_FIELD"; return $false }
    $b = $l[0].B
    Say ("  field0 [{0},{1}][{2},{3}] old='{4}'" -f $b['l'],$b['t'],$b['r'],$b['b'],(TxtOf $l[0].N))
    TapXY $b['cx'] $b['cy']; Wipe 120
    TypeS "ssh -p 2222 sshtest@$hostIp$tag"
    Start-Sleep -Milliseconds 600
    $null = Cap ("c4_H{0}_c" -f $idx)
    $hit = $false
    foreach ($e in (FormETs2)) { if ((TxtOf $e.N) -like "*$tag*") { $hit = $true; Say ("  SENTINEL = '{0}'" -f (TxtOf $e.N)) } }
    if (-not $hit) { Say "  TYPED_NOT_FOUND" }
    Say ("  BTN " + (BtnState "Add server"))
    $an = FindN "Add server" 0
    if (-not $an) { Say "  NO_ADD_SERVER"; return $false }
    $anc = ClickAncestor $an
    if (-not $anc) { Say "  NO_ANC"; return $false }
    $ab = Bnd $anc
    Say ("  TAP Add server @{0},{1}" -f $ab['cx'],$ab['cy'])
    TapXY $ab['cx'] $ab['cy']
    $null = Cap ("c4_H{0}_d" -f $idx)
    $null = IsANR
    $hasCard = $false
    foreach ($n in (Nodes)) { if ((TxtOf $n) -like "*password for this connection*") { $hasCard = $true } }
    Say ("  PASSWORD_CARD={0}" -f $hasCard)
    if ($hasCard) { $cn = FindN "Cancel" 0
        if ($cn) { $cb = Bnd $cn; Say ("  dismiss Cancel @{0},{1}" -f $cb['cx'],$cb['cy']); TapXY $cb['cx'] $cb['cy']; Start-Sleep -Milliseconds 500; $null = Cap ("c4_H{0}_e" -f $idx) } }
    $null = Cap ("c4_H{0}_f" -f $idx)
    $null = RowList ("after_{0}" -f $idx)
    return $hit
}

Say "=== BEFORE ==="
$null = RowList "baseline"
foreach ($p in @(@(2,"127.0.0.2"), @(3,"127.0.0.3"), @(4,"127.0.0.4"))) {
    $ok = MakeRowHost $p[0] $p[1]
    Say ("MAKE_ROW_{0}_OK={1}  SELF_ROWS_NOW={2}" -f $p[0], $ok, (SelfTitles).Count)
}
Say "=== AFTER ==="
$null = RowList "final"
$null = Cap "c4_HZ_final"
