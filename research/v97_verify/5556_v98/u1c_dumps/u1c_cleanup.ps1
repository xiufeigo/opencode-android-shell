$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$log=@()
A "am start -n com.xiufeigo.opencodeshell/.OcActivity" | Out-Null
Start-Sleep -Seconds 4
# open sessions drawer
$n = Get-Nodes (Dump-UI "cl0")
$open = $n | Where-Object { $_.Desc -eq 'Open sessions' } | Select-Object -First 1
if ($open) { A "input tap $([int](($open.B.X1+$open.B.X2)/2)) $([int](($open.B.Y1+$open.B.Y2)/2))" | Out-Null; Start-Sleep -Seconds 2 }
$n = Get-Nodes (Dump-UI "cl1")
$log += "--- session list entries (looking for my self-created ones) ---"
$n | Where-Object { $_.Text -and $_.B -and $_.W -lt 400 } | ForEach-Object {
    $log += "  '$($_.Text)' | $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)"
}
# report close buttons present
$log += "close-btns=" + (($n | Where-Object { $_.Desc -eq 'Close' }).Count)
$log | Set-Content -Encoding UTF8 "$d\u1c_cleanup.log"
$log | ForEach-Object { $_ }
