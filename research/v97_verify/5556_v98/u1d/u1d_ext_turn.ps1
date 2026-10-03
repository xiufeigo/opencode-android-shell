$ErrorActionPreference = 'Stop'
$d = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1d"
. "$d\u1d_lib.ps1"
$out = @()
$null = Assert-Focus "ext-turn"

function GetEt { (Get-Nodes (Dump-UI $args[0]) | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1) }

# 1) send a long turn
$et = GetEt "ext_t0"
$cx=[int](($et.B.X1+$et.B.X2)/2); $cy=[int](($et.B.Y1+$et.B.Y2)/2)
A "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 400
A "input keyevent 123" | Out-Null
1..40 | ForEach-Object { A "input keyevent 67" | Out-Null }
Type-Chunked "Count from 1 to 400 slowly, one number per line, with a short sentence for each"
Start-Sleep -Milliseconds 600
$n = Get-Nodes (Dump-UI "ext_t1")
$ime = Get-ImeState
$snd = $n | Where-Object { $_.Desc -eq 'Send' -or $_.Text -eq 'Send' } | Select-Object -First 1
$out += "mInputShown=$($ime.Shown)  Send found=$($snd -ne $null)"
if(-not $snd){
  $out += "candidates:"; $n | Where-Object { $_.B -and $_.B.Y1 -ge 170 -and $_.B.Y1 -le 240 } | ForEach-Object { $out += "   '$($_.Text)' '$($_.Desc)' @ $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)" }
  $out | Set-Content -Encoding UTF8 "$d\dumps\ext_turn.log"; $out; exit 1
}
$sx=[int](($snd.B.X1+$snd.B.X2)/2); $sy=[int](($snd.B.Y1+$snd.B.Y2)/2)
$out += "Send @ $sx,$sy"
A "input tap $sx $sy" | Out-Null
Start-Sleep -Seconds 4
$n = Get-Nodes (Dump-UI "ext_t2")
$out += "Stop(running)=" + (@($n | Where-Object { $_.Desc -eq 'Stop' }).Count -gt 0)
$out += "focus: " + (Get-Focus)
$out | Set-Content -Encoding UTF8 "$d\dumps\ext_turn.log"
$out
