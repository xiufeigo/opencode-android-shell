$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$log=@()
# open the sessions drawer again and locate the self-created session row
$n = Get-Nodes (Dump-UI "cx0")
$open = $n | Where-Object { $_.Desc -eq 'Open sessions' } | Select-Object -First 1
if ($open) { A "input tap $([int](($open.B.X1+$open.B.X2)/2)) $([int](($open.B.Y1+$open.B.Y2)/2))" | Out-Null; Start-Sleep -Seconds 2 }
# long-press my session row to reveal a Close button
$n = Get-Nodes (Dump-UI "cx1")
$mine = $n | Where-Object { $_.Text -eq 'Guide to Android Window Insets' -and $_.B -and $_.B.Y1 -gt 150 } | Select-Object -First 1
$log += "mine: " + $(if($mine){"$($mine.B.X1),$($mine.B.Y1)-$($mine.B.X2),$($mine.B.Y2)"}else{"not found"})
if ($mine) {
    $cx=[int](($mine.B.X1+$mine.B.X2)/2); $cy=[int](($mine.B.Y1+$mine.B.Y2)/2)
    A "input swipe $cx $cy $cx $cy 900" | Out-Null
    Start-Sleep -Seconds 2
    $n2 = Get-Nodes (Dump-UI "cx2")
    $close = $n2 | Where-Object { $_.Desc -eq 'Close' -and $_.B } | Sort-Object { [Math]::Abs($_.B.X1 - $cx) } | Select-Object -First 1
    $log += "close after longpress: " + $(if($close){"$($close.B.X1),$($close.B.Y1)-$($close.B.X2),$($close.B.Y2)"}else{"NONE"})
    if ($close) {
        A "input tap $([int](($close.B.X1+$close.B.X2)/2)) $([int](($close.B.Y1+$close.B.Y2)/2))" | Out-Null
        Start-Sleep -Seconds 2
        $log += "tapped close"
    } else {
        $log += "close buttons present: " + (($n2|Where-Object{$_.Desc -eq 'Close'}).Count)
        ($n2 | Where-Object { $_.B -and $_.B.Y1 -gt 150 -and ($_.Text -or $_.Desc) } | ForEach-Object { $log += "   '$($_.Text)' '$($_.Desc)' $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)" }) -join "`n" | Out-Null
    }
}
$n3 = Get-Nodes (Dump-UI "cx3")
$still = $n3 | Where-Object { $_.Text -eq 'Guide to Android Window Insets' -and $_.B -and $_.B.Y1 -gt 150 } | Select-Object -First 1
$log += "session still listed: " + ($still -ne $null)
$log | Set-Content -Encoding UTF8 "$d\u1c_cleanup2.log"
$log | ForEach-Object { $_ }
