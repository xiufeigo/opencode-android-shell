. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

# Stability: 5 rapid consecutive frames. Is timeline h=16 stable or transitional?
for ($i = 1; $i -le 5; $i++) {
    $f = DumpIt "60_stab$i"
    $ns = GetNodes $f
    $tl = $ns | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
    $st = @($ns | Where-Object { $_.Txt -eq 'Steer' }); $rm = @($ns | Where-Object { $_.Txt -eq 'Remove' })
    $qb = @($ns | Where-Object { $_.Txt -eq 'Queued' })
    $ed = $ns | Where-Object { $_.Cls -match 'EditText' } | Select-Object -First 1
    $sth = if ($st.Count) { "$($st[0].H)" } else { '-' }
    $rmh = if ($rm.Count) { "$($rm[0].H)" } else { '-' }
    Write-Output ("F{0} IME={1} TL=[{2},{3}][{4},{5}] h={6} QUEUED={7} STEER={8}(h={9}) REMOVE={10}(h={11}) EDT=[{12},{13}]h={14} bottom={15}" -f $i,(ImeState),$tl.X1,$tl.Y1,$tl.X2,$tl.Y2,$tl.H,$qb.Count,$st.Count,$sth,$rm.Count,$rmh,$ed.X1,$ed.Y1,$ed.H,$ed.Y2)
    Start-Sleep -Milliseconds 700
}
