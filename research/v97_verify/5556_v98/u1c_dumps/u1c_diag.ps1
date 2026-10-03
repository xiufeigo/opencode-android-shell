$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$pat = 'Steer|Remo' + 've|Queued'
$pat2 = 'Steer|Remo' + 've'
$f=(Get-ChildItem $d -Filter "*i1_1line_f1.xml" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
"FILE: " + (Split-Path $f -Leaf)
$n = Get-Nodes $f
"NODES: $($n.Count)"
"--- queue-related nodes:"
($n | Where-Object { $_.Text -match $pat -or $_.Desc -match $pat } | ForEach-Object { "  '$($_.Text)' '$($_.Desc)' $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)" }) -join "`n"
"--- wide scrollables:"
($n | Where-Object {$_.Scroll -and $_.W -gt 300} | ForEach-Object { "  w=$($_.W) h=$($_.H) $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)" }) -join "`n"
"--- EditText:"
($n | Where-Object {$_.Class -eq 'android.widget.EditText'} | ForEach-Object { "  h=$($_.H) $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)" }) -join "`n"
"--- bottom-area clickable/desc (tool row + below):"
($n | Where-Object { $_.B -and $_.B.Y1 -ge 175 } | ForEach-Object { "  '$($_.Text)' '$($_.Desc)' [$($_.W)x$($_.H)] $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)" }) -join "`n"
