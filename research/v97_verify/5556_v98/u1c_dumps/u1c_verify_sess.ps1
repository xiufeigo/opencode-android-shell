$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$log=@()
$n = Get-Nodes (Dump-UI "fv0")
$open = $n | Where-Object { $_.Desc -eq 'Open sessions' } | Select-Object -First 1
if ($open) { A "input tap $([int](($open.B.X1+$open.B.X2)/2)) $([int](($open.B.Y1+$open.B.Y2)/2))" | Out-Null; Start-Sleep -Seconds 2 }
$n2 = Get-Nodes (Dump-UI "fv1")
$log += "--- session list titles ---"
$n2 | Where-Object { $_.Text -and $_.B -and $_.W -lt 400 -and $_.B.Y1 -gt 150 } | ForEach-Object {
    $log += "  '$($_.Text)' @$($_.B.X1),$($_.B.Y1)"
}
$mine = $n2 | Where-Object { $_.Text -eq 'Guide to Android Window Insets' -and $_.B -and $_.B.Y1 -gt 150 } | Select-Object -First 1
$log += "MY SESSION IN LIST: " + ($mine -ne $null)
$log | Set-Content -Encoding UTF8 "$d\u1c_verify_sess.log"
$log | ForEach-Object { $_ }
