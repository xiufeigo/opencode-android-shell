$ErrorActionPreference = 'Stop'
$d = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1d"
. "$d\u1d_lib.ps1"
$out = @()

function Step { param([string]$m) $script:out += $m; $m }

# ---------- 1. clear composer + send a long turn ----------
$et = (Get-Nodes (Dump-UI "x_t0") | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1)
$cx=[int](($et.B.X1+$et.B.X2)/2); $cy=[int](($et.B.Y1+$et.B.Y2)/2)
A "input tap $cx $cy" | Out-Null; Start-Sleep -Milliseconds 400
A "input keyevent 123" | Out-Null
1..60 | ForEach-Object { A "input keyevent 67" | Out-Null }
Type-Chunked "Count 1 to 400 slowly"
Start-Sleep -Milliseconds 500
$n = Get-Nodes (Dump-UI "x_t1")
$snd = $n | Where-Object { $_.Desc -eq 'Send' -or $_.Text -eq 'Send' } | Select-Object -First 1
$etv = ($n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1)
Step "ime=$((Get-ImeState).Shown) draft='$($etv.Text)' sendFound=$($snd -ne $null)"
if(-not $snd){ Step "ABORT: no Send"; $out | Set-Content -Encoding UTF8 "$d\dumps\x.log"; exit 1 }
$sx=[int](($snd.B.X1+$snd.B.X2)/2); $sy=[int](($snd.B.Y1+$snd.B.Y2)/2)
A "input tap $sx $sy" | Out-Null
Start-Sleep -Seconds 3
$n = Get-Nodes (Dump-UI "x_t2")
$running = @($n | Where-Object { $_.Desc -eq 'Stop' }).Count -gt 0
Step "after send: running=$running"
if(-not $running){
  Step "retry: retype + send"
  $et = (Get-Nodes (Dump-UI "x_t3") | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1)
  A "input tap $([int](($et.B.X1+$et.B.X2)/2)) $([int](($et.B.Y1+$et.B.Y2)/2))" | Out-Null
  Start-Sleep -Milliseconds 300
  A "input keyevent 123" | Out-Null
  1..60 | ForEach-Object { A "input keyevent 67" | Out-Null }
  Type-Chunked "Count 1 to 400 slowly"
  Start-Sleep -Milliseconds 400
  $n = Get-Nodes (Dump-UI "x_t4")
  $snd = $n | Where-Object { $_.Desc -eq 'Send' -or $_.Text -eq 'Send' } | Select-Object -First 1
  if($snd){ A "input tap $([int](($snd.B.X1+$snd.B.X2)/2)) $([int](($snd.B.Y1+$snd.B.Y2)/2))" | Out-Null; Start-Sleep -Seconds 3 }
  $n = Get-Nodes (Dump-UI "x_t5")
  $running = @($n | Where-Object { $_.Desc -eq 'Stop' }).Count -gt 0
  Step "after retry: running=$running"
}
if(-not $running){ Step "ABORT: turn never started"; $out | Set-Content -Encoding UTF8 "$d\dumps\x.log"; exit 1 }

# ---------- 2. enqueue via Queue chip ----------
for($i=1; $i -le 12; $i++){
  $n = Get-Nodes (Dump-UI "x_e$i")
  $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
  if(-not $et){ Step "no ET at enq $i"; break }
  A "input tap $([int](($et.B.X1+$et.B.X2)/2)) $([int](($et.B.Y1+$et.B.Y2)/2))" | Out-Null
  Start-Sleep -Milliseconds 200
  $msg = "x{0:d2}" -f $i
  Type-Chunked $msg; Start-Sleep -Milliseconds 250
  $n2 = Get-Nodes (Dump-UI "x_e2_$i")
  $chip = $n2 | Where-Object { $_.Text -eq 'Queue' -and $_.B -and $_.B.Y1 -ge 440 -and $_.B.Y1 -le 560 } | Select-Object -First 1
  if(-not $chip){ $chip = $n2 | Where-Object { $_.Text -eq 'Queue' -and $_.B } | Select-Object -First 1 }
  if($chip){
    A "input tap $([int](($chip.B.X1+$chip.B.X2)/2)) $([int](($chip.B.Y1+$chip.B.Y2)/2))" | Out-Null
    Step "$msg enq @ $($chip.B.X1),$($chip.B.Y1)"
  } else { Step "$msg NO CHIP" }
  Start-Sleep -Milliseconds 300
  $n3 = Get-Nodes (Dump-UI "x_e3_$i")
  $ql = @($n3 | Where-Object { $_.Text -eq 'Queued' }).Count
  $sp = @($n3 | Where-Object { $_.Desc -eq 'Steer' -or $_.Text -eq 'Steer' }).Count
  Step "  depth(Queued labels)=$ql steer=$sp running=$(@($n3 | Where-Object { $_.Desc -eq 'Stop' }).Count -gt 0)"
}
$out | Set-Content -Encoding UTF8 "$d\dumps\x.log"
$out
