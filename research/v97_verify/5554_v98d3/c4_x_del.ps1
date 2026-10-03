. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4_d_lib.ps1"
InitDir

function RowState([string]$tag) {
    Say "  ---- STATE [$tag] ----"
    foreach ($n in (Nodes)) { $b = Bnd $n; if (-not $b) { continue }
        if ($b['t'] -lt 900 -or $b['t'] -gt 1990) { continue }
        $t = TxtOf $n; if (-not $t) { continue }
        if ($t -in @("Servers","Add SSH server","Default")) { continue }
        Say ("    [{0},{1}][{2},{3}] | {4}" -f $b['l'],$b['t'],$b['r'],$b['b'],$t) }
}

# 0) make sure no form is open so the list is fully visible
$cn = FindN "Cancel" 0
if ($cn) { $cb = Bnd $cn; Say ("close form: Cancel @{0},{1}" -f $cb['cx'],$cb['cy']); TapXY $cb['cx'] $cb['cy']; Start-Sleep -Milliseconds 600 }
$null = Cap "c4_X0_base"
Say "=== 0) BASELINE ==="
$null = RowList "baseline"
RowState "baseline"

# 1) locate MY self row's kebab (nearest kebab to my title)
$st = SelfTitles
if ($st.Count -lt 1) { Say "NO_SELF_ROW"; exit }
$mine = $null
foreach ($e in $st) { if ($e.T -like "*E2ECHK5554d3c*") { $mine = $e } }
Say ("  my row = '{0}' [{1},{2}][{3},{4}]" -f $mine.T,$mine.B['l'],$mine.B['t'],$mine.B['r'],$mine.B['b'])
$kb = $null; $bd = 99999
foreach ($e in (KebabList)) { $d = [math]::Abs($e.B['cy'] - $mine.B['cy']); if ($d -lt $bd) { $bd = $d; $kb = $e.B } }
Say ("  my kebab [{0},{1}][{2},{3}] @{4},{5} dy={6}" -f $kb['l'],$kb['t'],$kb['r'],$kb['b'],$kb['cx'],$kb['cy'],$bd)

# 2) D3-4 'Connecting': tap Reconnect, then delete IMMEDIATELY while connecting
$rc = FindN "Reconnect" 0
if ($rc) {
    $rb = Bnd $rc
    Say ("  >>> tap Reconnect @{0},{1} to enter Connecting" -f $rb['cx'],$rb['cy'])
    TapXY $rb['cx'] $rb['cy']
    Start-Sleep -Milliseconds 120
    $null = Cap "c4_X1_connecting"
    RowState "connecting_120ms"
    $conn = $false
    foreach ($n in (Nodes)) { $t = TxtOf $n; if ($t -eq "Connecting") { $conn = $true } }
    Say ("  CONNECTING_LABEL_SEEN={0}" -f $conn)
} else { Say "  no Reconnect button (row not reconnectable)" }

# 3) now delete: kebab -> Delete
Say ""
Say "=== D3 delete flow ==="
$null = Cap "c4_X2_predelete"
$null = RowList "pre_delete"
$kb = $null; $bd = 99999
foreach ($e in (KebabList)) { $d = [math]::Abs($e.B['cy'] - $mine.B['cy']); if ($d -lt $bd) { $bd = $d; $kb = $e.B } }
Say ("  tap kebab @{0},{1}" -f $kb['cx'],$kb['cy'])
TapXY $kb['cx'] $kb['cy']
$null = Cap "c4_X3_menu"
$dn = FindN "Delete" 0
if (-not $dn) { Say "  menu Delete NOT FOUND"; RowState "menu"; exit }
$db = Bnd $dn
Say ("  menu Delete [{0},{1}][{2},{3}] @{4},{5}" -f $db['l'],$db['t'],$db['r'],$db['b'],$db['cx'],$db['cy'])
TapXY $db['cx'] $db['cy']
$null = Cap "c4_X4_bar"

# 4) CONFIRM BAR GEOMETRY
$tt = FindN "Delete SSH server?" 0
if (-not $tt) { Say "  *** CONFIRM BAR NOT ARMED ***"; RowState "nobar"; exit }
$tb = Bnd $tt
$cd = $null; $cc = $null
foreach ($n in (Nodes)) { $t = TxtOf $n; $b = Bnd $n
    if ($b['t'] -lt $tb['b'] - 5) { continue }
    if ($b['t'] -gt $tb['b'] + 400) { continue }
    if ($t -eq "Delete" -and -not $cd) { $cd = $b }
    if ($t -eq "Cancel" -and -not $cc) { $cc = $b } }
$vp = Viewport
$sv = $null
foreach ($n in (Nodes)) { if ($n.GetAttribute("class") -eq "android.widget.ScrollView") { $b = Bnd $n; if ($b['t'] -ge 700) { $sv = $b; break } } }
Say ("  CONFIRM_TITLE  [{0},{1}][{2},{3}]" -f $tb['l'],$tb['t'],$tb['r'],$tb['b'])
if ($cd) { Say ("  CONFIRM_DELETE [{0},{1}][{2},{3}] @{4},{5}" -f $cd['l'],$cd['t'],$cd['r'],$cd['b'],$cd['cx'],$cd['cy']) }
if ($cc) { Say ("  CONFIRM_CANCEL [{0},{1}][{2},{3}]" -f $cc['l'],$cc['t'],$cc['r'],$cc['b']) }
if ($vp) { Say ("  A11Y_SCROLLABLE_VIEWPORT [{0},{1}][{2},{3}]" -f $vp['l'],$vp['t'],$vp['r'],$vp['b']) }
if ($sv) { Say ("  PANEL_SCROLLVIEW         [{0},{1}][{2},{3}]" -f $sv['l'],$sv['t'],$sv['r'],$sv['b']) }
$barB = if ($cd) { $cd['b'] } else { $tb['b'] }
Say ("  TARGET_KEBAB_Y={0}  TARGET_TITLE_Y={1}" -f $kb['t'], $mine.B['t'])
Say ("  CONFIRM_BAR_Y=[{0}, {1}]" -f $tb['t'], $barB)
Say ("  PANEL_GEOM_BOX_Y=[800, 1937]")
Say ("  GEOMETRY_FULLY_INSIDE_PANEL={0}" -f (($tb['t'] -ge 800) -and ($barB -le 1937)))
Say ("  KEBAB_ABOVE_FOLD_1937={0}  (if False, this is the beyond-fold path)" -f ($kb['t'] -lt 1937))

$pre = @(); foreach ($e in (SelfTitles)) { $pre += $e.T }
Say ("  PRE_DELETE_SELF = {0}" -f ($pre -join ' ; '))
$preRows = (KebabList).Count
Say ("  PRE_DELETE_TOTAL_ROWS={0}" -f $preRows)

if (-not $cd) { Say "  no confirm Delete"; exit }
Say ("  >>> tap confirm Delete @{0},{1}" -f $cd['cx'],$cd['cy'])
TapXY $cd['cx'] $cd['cy']
$null = Cap "c4_X5_deleted"
$null = IsANR
$barGone = $true
foreach ($n in (Nodes)) { if ((TxtOf $n) -eq "Delete SSH server?") { $barGone = $false } }
$post = @(); foreach ($e in (SelfTitles)) { $post += $e.T }
Say ("  POST_DELETE_SELF = {0}" -f ($post -join ' ; '))
Say ("  CONFIRM_BAR_GONE={0}" -f $barGone)
Say ("  POST_DELETE_TOTAL_ROWS={0}" -f (KebabList).Count)
$null = RowList "post_delete"
RowState "post_delete"
Say "=== DONE ==="
