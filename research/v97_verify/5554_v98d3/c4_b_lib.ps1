. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir

# --- row model: a server row = a Server-actions kebab + the title text nearest to it ---
function Kebabs {
    $l = @()
    foreach ($n in (Nodes)) {
        $t = TxtOf $n
        if ($t -eq "Server actions") { $b = Bnd $n; $l += ,@($n, $b) }
    }
    return @($l | Sort-Object { $_[1]['t'] })
}

# all candidate row-title texts inside the servers panel, excluding chrome
function RowTitles {
    $l = @()
    foreach ($n in (Nodes)) {
        $b = Bnd $n
        if (-not $b) { continue }
        if ($b['t'] -lt 950 -or $b['t'] -gt 1990) { continue }
        $t = TxtOf $n
        if (-not $t) { continue }
        if ($t -in @("Servers","Add SSH server","Default","SSH tunnels","Server healthy","Connecting","Connected","Disconnected")) { continue }
        $l += ,@($n, $b)
    }
    return @($l | Sort-Object { $_[1]['t'] })
}

function SelfRows {
    # self-made row titles start with the prefix; keep the longest match per y
    $l = @()
    foreach ($n in (Nodes)) {
        $t = TxtOf $n
        if ($t -like "E2ECHK5554d3c*") { $b = Bnd $n; $l += ,@($n, $b, $t) }
    }
    return @($l | Sort-Object { $_[1]['t'] })
}

function RowList([string]$tag) {
    Say "  ---- ROW LIST [$tag] ----"
    $k = Kebabs
    $i = 1
    foreach ($e in $k) { Say ("    kebab#{0} [{1},{2}][{3},{4}] @{5},{6}" -f $i,$e[1]['l'],$e[1]['t'],$e[1]['r'],$e[1]['b'],$e[1]['cx'],$e[1]['cy']); $i++ }
    Say "    KEBAB_COUNT=$($k.Count)"
    Say "    -- title-ish texts --"
    foreach ($e in (RowTitles)) { Say ("      [{0},{1}][{2},{3}] | {4}" -f $e[1]['l'],$e[1]['t'],$e[1]['r'],$e[1]['b'],(TxtOf $e[0])) }
    Say "    -- self-made --"
    foreach ($e in (SelfRows)) { Say ("      SELF [{0},{1}][{2},{3}] | {4}" -f $e[1]['l'],$e[1]['t'],$e[1]['r'],$e[1]['b'],$e[2]) }
    $sr = SelfRows
    Say "    SELF_COUNT=$($sr.Count)  TITLES=$((@($sr | ForEach-Object { $_[2] })) -join ' ; ')"
    return $sr.Count
}

# scroll the servers LIST (not the form): swipe in the list area
function ScrollList([int]$dir = -1, [int]$px = 400, [int]$dur = 200) {
    $y1 = 1600; $y2 = 1600 - $px
    if ($dir -gt 0) { $y1 = 1400; $y2 = 1400 + $px }
    FastScroll 540 $y1 $y2 $dur
}

# delete the self-made row whose title == $nm
function DeleteSelfRow([string]$nm) {
    Say ""
    Say "======== DELETE ROW '$nm' ========"
    $sr = SelfRows
    if ($sr.Count -eq 0) { Say "  no self rows"; return $false }
    $target = $null
    foreach ($e in $sr) { if ($e[2] -eq $nm) { $target = $e } }
    if (-not $target) { Say "  row '$nm' not visible (self: $((@($sr | ForEach-Object { $_[2] })) -join ';'))"; return $false }
    $tb = $target[1]
    Say ("  target row bounds=[{0},{1}][{2},{3}] @{4},{5}" -f $tb['l'],$tb['t'],$tb['r'],$tb['b'],$tb['cx'],$tb['cy'])
    $null = Cap ("c4_b_{0}_v_before" -f ($nm -replace '[^A-Za-z0-9]','_'))

    # nearest kebab = this row's action button
    $best = $null; $bd = 99999
    foreach ($e in (Kebabs)) {
        $d = [math]::Abs($e[1]['cy'] - $tb['cy'])
        if ($d -lt $bd) { $bd = $d; $best = $e }
    }
    if (-not $best) { Say "  no kebab found"; return $false }
    $kb = $best[1]
    Say ("  row kebab [{0},{1}][{2},{3}] @{4},{5} dy={6}" -f $kb['l'],$kb['t'],$kb['r'],$kb['b'],$kb['cx'],$kb['cy'],$bd)
    TapXY $kb['cx'] $kb['cy']
    $null = Cap ("c4_b_{0}_v_menu" -f ($nm -replace '[^A-Za-z0-9]','_'))

    # menu Delete
    $dn = FindN "Delete" 0
    if (-not ($dn -is [System.Xml.XmlElement])) { Say "  menu Delete NOT FOUND"; DumpT "menu"; return $false }
    $db = Bnd $dn
    Say ("  menu Delete [{0},{1}][{2},{3}] @{4},{5}" -f $db['l'],$db['t'],$db['r'],$db['b'],$db['cx'],$db['cy'])
    TapXY $db['cx'] $db['cy']
    $null = Cap ("c4_b_{0}_v_bar" -f ($nm -replace '[^A-Za-z0-9]','_'))

    # ---- confirm bar geometry ----
    $tt = FindN "Delete SSH server?" 0
    if (-not ($tt -is [System.Xml.XmlElement])) { Say "  *** CONFIRM BAR NOT ARMED ***"; DumpT "noBar"; return $false }
    $tbq = Bnd $tt
    # the confirm bar's own Delete is the one BELOW the title
    $cd = $null
    foreach ($n in (Nodes)) {
        $t = TxtOf $n
        if ($t -ne "Delete") { continue }
        $b = Bnd $n
        if ($b['t'] -lt $tbq['b'] - 5) { continue }
        if ($b['t'] -gt $tbq['b'] + 400) { continue }
        $cd = $n; break
    }
    if (-not $cd) { Say "  no confirm-bar Delete node found" }
    $cdb = if ($cd) { Bnd $cd } else { $null }
    $cc = $null
    foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Cancel") { $b = Bnd $n; if ($b['t'] -ge $tbq['b'] - 5 -and $b['t'] -le $tbq['b'] + 400) { $cc = $b; break } } }
    $vp = Viewport
    Say ("  CONFIRM_TITLE [{0},{1}][{2},{3}]" -f $tbq['l'],$tbq['t'],$tbq['r'],$tbq['b'])
    if ($cdb) { Say ("  CONFIRM_DELETE [{0},{1}][{2},{3}] @{4},{5}" -f $cdb['l'],$cdb['t'],$cdb['r'],$cdb['b'],$cdb['cx'],$cdb['cy']) }
    if ($cc)  { Say ("  CONFIRM_CANCEL [{0},{1}][{2},{3}]" -f $cc['l'],$cc['t'],$cc['r'],$cc['b']) }
    if ($vp)  { Say ("  A11Y_VIEWPORT [{0},{1}][{2},{3}]" -f $vp['l'],$vp['t'],$vp['r'],$vp['b']) }
    Say  ("  PANEL_GEOM_BOX_Y = [800, 1937]  (round-3 proxy for the panel body viewport)")
    Say  ("  CONFIRM_BAR_Y = [{0}, {1}]" -f $tbq['t'], $(if ($cdb) { $cdb['b'] } else { $tbq['b'] }))
    Say  ("  GEOMETRY_IN_PANEL = " + ($tbq['t'] -ge 800 -and $(if ($cdb) { $cdb['b'] } else { $tbq['b'] }) -le 1937))

    # pre-delete census for the counter-example
    Say "  -- PRE-DELETE rows --"
    $null = RowList ("pre_" + $nm)

    if (-not $cdb) { Say "  confirm Delete not found -> abort"; return $false }
    TapXY $cdb['cx'] $cdb['cy']
    $null = Cap ("c4_b_{0}_v_deleted" -f ($nm -replace '[^A-Za-z0-9]','_'))
    Say "  -- POST-DELETE rows --"
    $cnt = RowList ("post_" + $nm)
    $gone = $true
    foreach ($e in (SelfRows)) { if ($e[2] -eq $nm) { $gone = $false } }
    $barGone = $true
    foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Delete SSH server?") { $barGone = $false } }
    Say ("  TARGET_GONE={0}  CONFIRM_BAR_GONE={1}  SELF_LEFT={2}" -f $gone, $barGone, $cnt)
    return $gone
}
