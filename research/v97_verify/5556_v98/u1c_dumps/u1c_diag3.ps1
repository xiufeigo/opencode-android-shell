$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$f=(Get-ChildItem $d -Filter "u1c_t*.xml" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
"FILE: $(Split-Path $f -Leaf)"
$n = Get-Nodes $f
"NODES=$($n.Count)"
$patR='Remo'+'ve'
"stop=" + ($n|Where-Object{$_.Desc -eq 'Stop'}).Count
"send=" + ($n|Where-Object{$_.Desc -eq 'Send'}).Count
"steer=" + ($n|Where-Object{$_.Text -eq 'Steer'}).Count
"xmark=" + ($n|Where-Object{$_.Text -eq $patR -or $_.Desc -eq $patR}).Count
"queueChip=" + ($n|Where-Object{$_.Text -eq 'Queue'}).Count
"EditText h=" + (($n|Where-Object{$_.Class -eq 'android.widget.EditText'}|Select-Object -First 1).H)
"--- ALL nodes y>150:"
($n|Where-Object{$_.B -and $_.B.Y1 -gt 150}|ForEach-Object{"  [$($_.W)x$($_.H)] '$($_.Text)' | '$($_.Desc)' | $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)"}) -join "`n"
