. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

function SendNode([string]$p) {
    foreach ($n in (GetNodes $p)) { if (($n.Txt -eq 'Send' -or $n.Txt -eq 'Stop') -and $n.Y1 -ge 246 -and $n.Y1 -le 300) { return $n } }
    return $null
}
function EditNode([string]$p) {
    foreach ($n in (GetNodes $p)) { if ($n.Cls -match 'EditText' -and $n.W -gt 200) { return $n } }
    return $null
}

# queue mode is ON (verified: Steer/Remove in tree). Fire 11 more messages.
$f = DumpIt "44_pre_fire"
$ed = EditNode $f
if ($ed) { Ado "input tap $([int](($ed.X1+$ed.X2)/2)) $([int](($ed.Y1+$ed.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 300 }
$sd = SendNode $f
$sx = [int](($sd.X1 + $sd.X2) / 2); $sy = [int](($sd.Y1 + $sd.Y2) / 2)
Write-Output "SEND=$sx,$sy IME=$(ImeState)"

for ($i = 2; $i -le 12; $i++) {
    TypeTxt "Q$i u1b"
    Start-Sleep -Milliseconds 110
    Ado "input tap $sx $sy" | Out-Null
    Start-Sleep -Milliseconds 230
}
Write-Output "FIRED_11 IME=$(ImeState) IMETOP=$(ImeTopPx)"

# ---- MEASURE ----
$v = DumpIt "45_q11_measure"
$ns = GetNodes $v
Write-Output "===== MEASURE q>=11 (IME open) ====="
Write-Output "NODES=$($ns.Count) mInputShown=$(ImeState) IMETOP=$(ImeTopPx)"
$tl = $ns | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
Write-Output ("TIMELINE [{0},{1}][{2},{3}] h={4} w={5}" -f $tl.X1,$tl.Y1,$tl.X2,$tl.Y2,$tl.H,$tl.W)
Write-Output "--- ALL scrollables (queue container detection) ---"
foreach ($n in ($ns | Where-Object { $_.Scroll -eq 'true' })) { Write-Output ("  SCROLL [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) cls=$($n.Cls)") }
$st = @($ns | Where-Object { $_.Txt -eq 'Steer' })
$rm = @($ns | Where-Object { $_.Txt -eq 'Remove' })
Write-Output "STEER_NODES=$($st.Count) REMOVE_NODES=$($rm.Count)"
foreach ($n in $st) { Write-Output ("  STEER [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) click=$($n.Click)") }
foreach ($n in $rm) { Write-Output ("  REMOVE [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) click=$($n.Click)") }
$qr = @($ns | Where-Object { $_.Txt -eq 'Queued' })
Write-Output "QUEUED_BADGES=$($qr.Count)"
$rows = @($ns | Where-Object { $_.Y1 -ge 168 -and $_.Y1 -le 230 -and $_.X1 -ge 20 -and $_.X1 -le 100 -and $_.H -gt 0 -and $_.Txt })
Write-Output "--- row height candidates (left gutter y168..230) ---"
foreach ($n in $rows) { Write-Output ("  ROWNODE [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) '$($n.Txt)'") }
$ed2 = $ns | Where-Object { $_.Cls -match 'EditText' } | Select-Object -First 1
if ($ed2) { Write-Output ("EDITTEXT [$($ed2.X1),$($ed2.Y1)][$($ed2.X2),$($ed2.Y2)] h=$($ed2.H) bottom=$($ed2.Y2)") }
ShotIt "45_q11_measure" | Out-Null
