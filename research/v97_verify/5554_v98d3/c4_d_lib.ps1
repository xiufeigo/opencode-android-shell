. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir

function SelfTitles {
    $o = @()
    foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -like "*E2ECHK5554d3c*") { $b = Bnd $n; $o += [PSCustomObject]@{ T = $t; B = $b } } }
    return @($o | Sort-Object { $_.B['t'] })
}
function KebabList {
    $l = @(); foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Server actions") { $l += [PSCustomObject]@{ B = (Bnd $n) } } }
    return @($l | Sort-Object { $_.B['t'] })
}
function RowList([string]$tag) {
    Say "  ---- ROW LIST [$tag] ----"
    $k = KebabList
    for ($i = 0; $i -lt $k.Count; $i++) {
        Say ("    row#{0} kebab [{1},{2}][{3},{4}] @{5},{6}" -f ($i+1),$k[$i].B['l'],$k[$i].B['t'],$k[$i].B['r'],$k[$i].B['b'],$k[$i].B['cx'],$k[$i].B['cy'])
    }
    Say ("    TOTAL_ROWS={0}" -f $k.Count)
    $s = SelfTitles
    foreach ($e in $s) { Say ("    SELF [{0},{1}][{2},{3}] | {4}" -f $e.B['l'],$e.B['t'],$e.B['r'],$e.B['b'],$e.T) }
    Say ("    SELF_COUNT={0}" -f $s.Count)
    return $k.Count
}
function ScrollList([int]$px = 300) { FastScroll 540 1600 (1600 - $px) 200 }

# delete the Nth (1-based) row overall. returns @{ok=..}
function DeleteNthRow([int]$n) {
    Say ""
    Say "======== D3-3: DELETE ROW #$n ========"
    # make sure the row's kebab is on screen: scroll until we have >= n kebabs
    for ($k = 0; $k -lt 4; $k++) {
        $kk = KebabList
        if ($kk.Count -ge $n) { break }
        Say ("  only {0} rows visible -> scroll list" -f $kk.Count)
        ScrollList 300
        $null = Cap ("c4_D_n{0}_sc{1}" -f $n, $k)
    }
    $kk = KebabList
    if ($kk.Count -lt $n) { Say ("  *** row #{0} not reachable (only {1} rows) ***" -f $n, $kk.Count); return $false }
    $null = RowList ("pre_n{0}" -f $n)

    $kb = $kk[$n - 1].B
    Say ("  target row#{0} kebab [{1},{2}][{3},{4}] @{5},{6}" -f $n,$kb['l'],$kb['t'],$kb['r'],$kb['b'],$kb['cx'],$kb['cy'])
    TapXY $kb['cx'] $kb['cy']
    $null = Cap ("c4_D_n{0}_menu" -f $n)
    $dn = FindN "Delete" 0
    if (-not $dn) { Say "  menu Delete NOT FOUND"; return $false }
    $db = Bnd $dn
    Say ("  menu Delete [{0},{1}][{2},{3}] @{4},{5}" -f $db['l'],$db['t'],$db['r'],$db['b'],$db['cx'],$db['cy'])
    TapXY $db['cx'] $db['cy']
    $null = Cap ("c4_D_n{0}_bar" -f $n)

    # ---- confirm bar geometry ----
    $tt = FindN "Delete SSH server?" 0
    if (-not $tt) { Say "  *** CONFIRM BAR NOT ARMED ***"; return $false }
    $tb = Bnd $tt
    $cd = $null; $cc = $null
    foreach ($n2 in (Nodes)) {
        $t = TxtOf $n2; $b = Bnd $n2
        if ($b['t'] -lt $tb['b'] - 5) { continue }
        if ($b['t'] -gt $tb['b'] + 400) { continue }
        if ($t -eq "Delete" -and -not $cd) { $cd = $b }
        if ($t -eq "Cancel" -and -not $cc) { $cc = $b }
    }
    $vp = Viewport
    $sv = $null
    foreach ($n2 in (Nodes)) { if ($n2.GetAttribute("class") -eq "android.widget.ScrollView") { $b = Bnd $n2; if ($b['t'] -ge 700) { $sv = $b; break } } }
    Say ("  CONFIRM_TITLE  [{0},{1}][{2},{3}]" -f $tb['l'],$tb['t'],$tb['r'],$tb['b'])
    if ($cd) { Say ("  CONFIRM_DELETE [{0},{1}][{2},{3}] @{4},{5}" -f $cd['l'],$cd['t'],$cd['r'],$cd['b'],$cd['cx'],$cd['cy']) }
    if ($cc) { Say ("  CONFIRM_CANCEL [{0},{1}][{2},{3}]" -f $cc['l'],$cc['t'],$cc['r'],$cc['b']) }
    if ($vp) { Say ("  A11Y_SCROLLABLE_VIEWPORT [{0},{1}][{2},{3}]" -f $vp['l'],$vp['t'],$vp['r'],$vp['b']) }
    if ($sv) { Say ("  PANEL_SCROLLVIEW         [{0},{1}][{2},{3}]" -f $sv['l'],$sv['t'],$sv['r'],$sv['b']) }
    $barB = if ($cd) { $cd['b'] } else { $tb['b'] }
    Say ("  CONFIRM_BAR_Y = [{0}, {1}]" -f $tb['t'], $barB)
    Say ("  PANEL_GEOM_BOX_Y = [800, 1937] (round-3 viewport proxy)")
    Say ("  GEOMETRY_FULLY_INSIDE_PANEL = {0}" -f (($tb['t'] -ge 800) -and ($barB -le 1937)))
    Say ("  TARGET_KEBAB_Y = {0}  (above fold line 1937? {1})" -f $kb['t'], ($kb['t'] -lt 1937))

    if (-not $cd) { Say "  no confirm Delete -> abort"; return $false }
    $preSelf = @(); foreach ($e in (SelfTitles)) { $preSelf += $e.T }
    Say ("  PRE_DELETE_SELF = {0}" -f ($preSelf -join ' ; '))
    TapXY $cd['cx'] $cd['cy']
    $null = Cap ("c4_D_n{0}_deleted" -f $n)
    $null = IsANR
    # census after: scroll a couple of times to be sure
    for ($k = 0; $k -lt 2; $k++) { ScrollList 300; $null = Cap ("c4_D_n{0}_as{1}" -f $n, $k) }
    $postSelf = @(); foreach ($e in (SelfTitles)) { $postSelf += $e.T }
    Say ("  POST_DELETE_SELF = {0}" -f ($postSelf -join ' ; '))
    $barGone = $true
    foreach ($n2 in (Nodes)) { if ((TxtOf $n2) -eq "Delete SSH server?") { $barGone = $false } }
    Say ("  CONFIRM_BAR_GONE={0}" -f $barGone)
    $null = RowList ("post_n{0}" -f $n)
    return $true
}

