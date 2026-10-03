. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir

# ---- baseline row census ----
$null = Cap "c4_s01_base"
Say "=== BASELINE ROWS (before any self-made entry) ==="
$null = RowCensus "s01_base"
Say ("KEBAB_COUNT=" + (CountKebab))

# ---- open Add SSH server form ----
Say "=== OPEN FORM ==="
$ok = TapTxt "Add SSH server" 0
Say "TAP_OK=$ok"
$x = Cap "c4_s02_form_open"
Say "XML=$x"
$vp = Viewport
if ($vp) { Say ("FORM_VIEWPORT=[{0},{1}][{2},{3}]" -f $vp['l'],$vp['t'],$vp['r'],$vp['b']) }
DumpT "s02_form_open"
Say ("BTN_DEFAULT " + (BtnState "Add server"))
$null = DumpETs "s02_form_open"

# ---- pick a swipe anchor inside the form card, not on an EditText, not in keyboard zone ----
function PickSwipeY {
    $ets = @()
    foreach ($n in (DumpET)) { $b = Bnd $n; $ets += ,@($b['t'], $b['b']) }
    $cands = @()
    foreach ($y in 1450..1880) {
        $bad = $false
        foreach ($r in $ets) { if ($y -ge $r[0] -and $y -le $r[1]) { $bad = $true; break } }
        if (-not $bad) { $cands += $y }
    }
    if ($cands.Count -eq 0) { return 1520 }
    # prefer a point away from the mid so the swipe start is not near a field edge
    $pick = $cands[0]
    foreach ($y in $cands) { if ([math]::Abs($y - 1650) -lt [math]::Abs($pick - 1650)) { $pick = $y } }
    return $pick
}

# ---- fast scroll the form: 100px / 80ms, 5 steps ----
for ($i = 1; $i -le 5; $i++) {
    $y = PickSwipeY
    Say "--- scroll step $i : swipe x=800 y=$y -> $($y-100) dur=80 ---"
    FastScroll 800 $y ($y - 100) 80
    $x = Cap ("c4_s03_sc{0}" -f $i)
    Say "XML=$x"
    $n = DumpETs ("s03_sc{0}" -f $i)
    Say ("ET_COUNT={0}" -f $n)
    Say ("BTN " + (BtnState "Add server"))
    $vv = Viewport
    if ($vv) { Say ("VIEWPORT=[{0},{1}][{2},{3}]" -f $vv['l'],$vv['t'],$vv['r'],$vv['b']) }
}
Say "=== DONE s03 ==="
$null = IsANR
