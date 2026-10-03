. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'
function Dump2([string]$t) { for ($i=1; $i -le 4; $i++) { $p = DumpIt $t; if ($p -and (Test-Path $p) -and (Get-Item $p).Length -gt 500) { return $p }; Start-Sleep -Milliseconds 400 }; return $null }

ShotIt "b01_q16_evidence" | Out-Null
for ($k = 1; $k -le 4; $k++) {
    $v = Dump2 "b02_frame$k"
    if (-not $v) { Write-Output "F$k DUMP_FAIL"; continue }
    $ns = GetNodes $v
    $tl = $ns | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
    $st = @($ns | Where-Object { $_.Txt -eq 'Steer' }); $rm = @($ns | Where-Object { $_.Txt -eq 'Remove' })
    $qb = @($ns | Where-Object { $_.Txt -eq 'Queued' })
    $row = $ns | Where-Object { $_.W -gt 1000 -and $_.Y1 -ge 150 -and $_.Y1 -le 250 -and $_.Click -eq 'true' } | Select-Object -First 1
    $ed = $ns | Where-Object { $_.Cls -match 'EditText' } | Select-Object -First 1
    $rowTxt = if ($row) { "[$($row.X1),$($row.Y1)][$($row.X2),$($row.Y2)] w=$($row.W) h=$($row.H)" } else { 'none' }
    Write-Output ("F{0} IME={1} TL=[{2},{3}][{4},{5}] h={6} | QROW={7} | QUEUED={8} STEER={9} REMOVE={10} | EDT=[{11},{12}]h={13} bottom={14} IMETOP={15}" -f $k,(ImeState),$tl.X1,$tl.Y1,$tl.X2,$tl.Y2,$tl.H,$rowTxt,$qb.Count,$st.Count,$rm.Count,$ed.X1,$ed.Y1,$ed.H,$ed.Y2,(ImeTopPx))
    if ($k -le 2) {
        foreach ($n in $st) { Write-Output ("    STEER [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click)") }
        foreach ($n in $rm) { Write-Output ("    REMOVE [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click)") }
    }
    Start-Sleep -Milliseconds 500
}
