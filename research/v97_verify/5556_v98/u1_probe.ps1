. C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1_lib.ps1
$f = DumpIt "78_steer_probe"
$ns = GetNodes $f
Write-Output "TOTAL_NODES=$($ns.Count)"

$st = @($ns | Where-Object { $_.Txt -and $_.Txt.ToLower().Contains('steer') })
Write-Output "STEER_NODES=$($st.Count)"
foreach($h in $st){ Write-Output ("  STEER [$($h.X1),$($h.Y1)][$($h.X2),$($h.Y2)] h=$($h.H) clk=$($h.Click) '$($h.Txt)'") }

$mk = @($ns | Where-Object { $_.Txt -and ($_.Txt.Contains([char]0x00D7) -or $_.Txt.Contains([char]0x2715) -or $_.Txt.Contains([char]0x2716)) })
Write-Output "XMARK_NODES=$($mk.Count)"

$q  = @($ns | Where-Object { $_.Txt -and $_.Txt.ToLower().Contains('queue') })
Write-Output "QUEUE_TEXT_NODES=$($q.Count)"
foreach($h in $q){ Write-Output ("  Q [$($h.X1),$($h.Y1)][$($h.X2),$($h.Y2)] '$($h.Txt)'") }

$rows = @($ns | Where-Object { $_.X1 -eq 1316 -and $_.H -le 6 })
if($rows.Count -gt 0){
  $mn = ($rows | Measure-Object H -Minimum).Minimum
  $mx = ($rows | Measure-Object H -Maximum).Maximum
  $y0 = ($rows | Measure-Object Y1 -Minimum).Minimum
  $y1 = ($rows | Measure-Object Y2 -Maximum).Maximum
  Write-Output "RAIL_ROWS=$($rows.Count) H_MIN=$mn H_MAX=$mx Y_SPAN=$y0..$y1"
}
$sv = @($ns | Where-Object { $_.Cls -eq 'android.widget.ScrollView' })
foreach($h in $sv){ Write-Output ("  SCROLLVIEW [$($h.X1),$($h.Y1)][$($h.X2),$($h.Y2)] h=$($h.H) scroll=$($h.Scroll)") }

$sc = $ns | Where-Object { $_.Scroll -eq 'true' -and $_.W -gt 1000 } | Select-Object -First 1
$ed = $ns | Where-Object { $_.Cls -eq 'android.widget.EditText' } | Select-Object -First 1
Write-Output "TL=[$($sc.X1),$($sc.Y1)][$($sc.X2),$($sc.Y2)] h=$($sc.H)"
Write-Output "EDT=[$($ed.X1),$($ed.Y1)][$($ed.X2),$($ed.Y2)] h=$($ed.H)"
Write-Output "IME=$(ImeState) IMETOP=$(ImeTopPx)"
ShotIt "78_steer_probe" | Out-Null
