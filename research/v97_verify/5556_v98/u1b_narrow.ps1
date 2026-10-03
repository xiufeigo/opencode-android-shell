. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1b_lib2.ps1
$ErrorActionPreference = 'Continue'

function Dump2([string]$tag) {
    for ($t = 1; $t -le 3; $t++) {
        $p = DumpIt $tag
        if ($p) {
            $len = (Get-Item $p).Length
            if ($len -gt 500) { return $p }
        }
        Start-Sleep -Milliseconds 500
    }
    return $p
}

Write-Output "=== PRE-EXTREME baseline ==="
$b = Dump2 "70_base"
Write-Output ("WM_SIZE: " + ((Ado "wm size") -join ' | '))
Write-Output ("WM_DENSITY: " + ((Ado "wm density") -join ' | '))

Write-Output "=== SET EXTREME 1366x600 @160 ==="
Ado "wm size 1366x600" | Out-Null
Ado "wm density 160" | Out-Null
Start-Sleep -Seconds 3
Write-Output ("WM_SIZE_NOW: " + ((Ado "wm size") -join ' | '))
Write-Output ("WM_DENSITY_NOW: " + ((Ado "wm density") -join ' | '))

$f = Dump2 "71_narrow"
$ns = GetNodes $f
$tl = $ns | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
$st = @($ns | Where-Object { $_.Txt -eq 'Steer' }); $rm = @($ns | Where-Object { $_.Txt -eq 'Remove' })
$qb = @($ns | Where-Object { $_.Txt -eq 'Queued' })
$ed = $ns | Where-Object { $_.Cls -match 'EditText' } | Select-Object -First 1
Write-Output "===== EXTREME MEASURE ====="
Write-Output "mInputShown=$(ImeState) IMETOP=$(ImeTopPx) NODES=$($ns.Count)"
Write-Output ("TL=[{0},{1}][{2},{3}] h={4}" -f $tl.X1,$tl.Y1,$tl.X2,$tl.Y2,$tl.H)
Write-Output "QUEUED=$($qb.Count) STEER=$($st.Count) REMOVE=$($rm.Count)"
foreach ($n in $st) { Write-Output ("  STEER [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click)") }
foreach ($n in $rm) { Write-Output ("  REMOVE [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H) clk=$($n.Click)") }
if ($ed) { Write-Output ("EDT=[$($ed.X1),$($ed.Y1)][$($ed.X2),$($ed.Y2)] h=$($ed.H) bottom=$($ed.Y2)") }
Write-Output "--- all scrollables ---"
foreach ($n in ($ns | Where-Object { $_.Scroll -eq 'true' })) { Write-Output ("  SCROLL [$($n.X1),$($n.Y1)][$($n.X2),$($n.Y2)] w=$($n.W) h=$($n.H)") }
ShotIt "71_narrow" | Out-Null

Write-Output "=== RESET ==="
Ado "wm size reset" | Out-Null
Ado "wm density reset" | Out-Null
Start-Sleep -Seconds 3
Write-Output ("WM_SIZE_AFTER: " + ((Ado "wm size") -join ' | '))
Write-Output ("WM_DENSITY_AFTER: " + ((Ado "wm density") -join ' | '))
$f2 = Dump2 "72_restored"
$ns2 = GetNodes $f2
$tl2 = $ns2 | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 800 } | Select-Object -First 1
$st2 = @($ns2 | Where-Object { $_.Txt -eq 'Steer' }); $rm2 = @($ns2 | Where-Object { $_.Txt -eq 'Remove' })
Write-Output ("RESTORED TL h=$($tl2.H) STEER=$($st2.Count) REMOVE=$($rm2.Count) IME=$(ImeState) IMETOP=$(ImeTopPx)")
