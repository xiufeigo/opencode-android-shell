$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$p = Dump-UI "diag_now"
$n = Get-Nodes $p
"NODES=$($n.Count)"
$patR='Remo'+'ve'
"stop=" + ($n|Where-Object{$_.Desc -eq 'Stop'}).Count
"send=" + ($n|Where-Object{$_.Desc -eq 'Send'}).Count
"steer=" + ($n|Where-Object{$_.Text -eq 'Steer'}).Count
"xmark=" + ($n|Where-Object{$_.Text -eq $patR -or $_.Desc -eq $patR}).Count
"EditText h=" + (($n|Where-Object{$_.Class -eq 'android.widget.EditText'}|Select-Object -First 1).H)
"--- tool row (y 240-300) all text/desc:"
($n|Where-Object{$_.B -and $_.B.Y1 -ge 240 -and $_.B.Y1 -le 300 -and ($_.Text -or $_.Desc)}|ForEach-Object{"  '$($_.Text)' | '$($_.Desc)' | $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)"}) -join "`n"
"--- focus:"; (Get-Focus)
"--- ime:"; (Get-ImeState).Raw
