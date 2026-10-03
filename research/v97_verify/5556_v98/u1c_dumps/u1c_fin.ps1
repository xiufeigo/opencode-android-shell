$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$log=@()
$n = Get-Nodes (Dump-UI "fin0")
$del = $n | Where-Object { $_.Text -eq 'Delete' -and $_.B } | Select-Object -First 1
$log += "Delete node: " + $(if($del){"$($del.B.X1),$($del.B.Y1)-$($del.B.X2),$($del.B.Y2)"}else{"NONE"})
if ($del) {
    A "input tap $([int](($del.B.X1+$del.B.X2)/2)) $([int](($del.B.Y1+$del.B.Y2)/2))" | Out-Null
    Start-Sleep -Seconds 2
    # possible confirm dialog
    $n2 = Get-Nodes (Dump-UI "fin1")
    $log += "after Delete, dialog nodes:"
    $n2 | Where-Object { $_.B -and ($_.Text -or $_.Desc) -and $_.W -lt 800 } | ForEach-Object {
        $log += "   '$($_.Text)' '$($_.Desc)' $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2) clk=$($_.Click)"
    }
    # try to confirm
    $cf = $n2 | Where-Object { ($_.Text -match '^(Confirm|Delete|OK|Yes)$') -and $_.Click } | Select-Object -First 1
    if ($cf) { $log += "confirm: '$($cf.Text)'"; A "input tap $([int](($cf.B.X1+$cf.B.X2)/2)) $([int](($cf.B.Y1+$cf.B.Y2)/2))" | Out-Null; Start-Sleep -Seconds 2 }
}
$n3 = Get-Nodes (Dump-UI "fin2")
$mine = $n3 | Where-Object { $_.Text -eq 'Guide to Android Window Insets' -and $_.B } | Select-Object -First 1
$log += "my session still present: " + ($mine -ne $null)
$log += "focus: " + (Get-Focus)
$log | Set-Content -Encoding UTF8 "$d\u1c_fin.log"
$log | ForEach-Object { $_ }
