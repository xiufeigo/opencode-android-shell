$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$log=@()
$patR = 'Remo' + 've'

function Read-Deep {
    param([string]$tag)
    $ime = Get-ImeState
    $p = Dump-UI $tag
    $m = Measure-Frame $p
    $n = Get-Nodes $p
    $steerN = ($n | Where-Object { $_.Text -eq 'Steer' -or $_.Desc -eq 'Steer' }).Count
    $xN     = ($n | Where-Object { $_.Text -eq $patR -or $_.Desc -eq $patR }).Count
    $line = "$tag | ime=$($ime.Shown) | tl=$($m.timeline) tlH=$($m.tlH) | qb=$($m.queueBar) | ov=$($m.overlap) | READABLE=$($m.readable) | steer=$($m.steer.b) h=$($m.steer.h) clk=$($m.steer.clk) n=$steerN | x=$($m.xmark.b) h=$($m.xmark.h) clk=$($m.xmark.clk) n=$xN | qvp=$($m.queueVP) qH=$($m.qH) qline=$($m.queueLine) | etH=$($m.etH)"
    $script:log += $line
    $line
}

# ---- ensure a running turn so the queue bar exists ----
$st = (Get-Nodes (Dump-UI "u1c_d_st0")) | Where-Object { $_.Desc -eq 'Stop' }
if (-not $st) {
    $log += "no running turn -> trigger"
    $n = Get-Nodes (Dump-UI "u1c_d_t0")
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 500 }
    A "input keycombination 113 29" | Out-Null; A "input keyevent 67" | Out-Null
    Type-Chunked "Count from 1 to 200 slowly"
    Start-Sleep -Milliseconds 500
    $n2 = Get-Nodes (Dump-UI "u1c_d_t1")
    $snd = $n2 | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
    if ($snd) { Tap-Node $snd | Out-Null; Start-Sleep -Seconds 3 }
    $n = Get-Nodes (Dump-UI "u1c_d_t2")
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 500 }
    Type-Chunked "toggle queue"
    Start-Sleep -Milliseconds 800
    $n = Get-Nodes (Dump-UI "u1c_d_t3")
    $chip = Find-Exact $n "Queue" 246 298
    $log += "chip re-toggle: " + ($chip -ne $null)
    if ($chip) { Tap-Node $chip | Out-Null; Start-Sleep -Milliseconds 600 }
}

# ---- push queue depth to >= 11 ----
for ($i=1; $i -le 14; $i++) {
    $n = Get-Nodes (Dump-UI "u1c_d_p$i")
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if (-not $et) { $log += "no ET at $i"; break }
    Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 350
    A "input keycombination 113 29" | Out-Null; A "input keyevent 67" | Out-Null
    Start-Sleep -Milliseconds 150
    Type-Chunked "Queue depth filler number $i"
    Start-Sleep -Milliseconds 400
    $n2 = Get-Nodes (Dump-UI "u1c_d_s$i")
    $snd = $n2 | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
    if ($snd) { Tap-Node $snd | Out-Null } else { $log += "NO SEND at $i"; break }
    Start-Sleep -Milliseconds 450
    # depth marker
    $n3 = Get-Nodes (Dump-UI "u1c_d_c$i")
    $q = $n3 | Where-Object { $_.Desc -match 'Queued p rompt|Queue dep th fil' } | Measure-Object
    $log += "depth-marked-nodes after #${i}: $($q.Count)"
    if ($q.Count -ge 11) { $log += "reached >=11 at #$i"; break }
}

$log += "=== depth measurements ==="
Read-Deep "u1c_d_before_scroll" | Out-Null

# ---- scroll inside the queue bar ----
$nodes = Get-Nodes (Dump-UI "u1c_d_sc0")
$holder = $nodes | Where-Object { $_.B -and $_.W -gt 500 -and (($nodes | Where-Object { $_.Text -eq 'Steer' }).Count -gt 0) } | Select-Object -First 1
$qvp = $nodes | Where-Object { $_.Scroll -and $_.W -gt 500 -and $_.B -and $_.B.Y1 -gt 100 } | Sort-Object { $_.B.Y1 } | Select-Object -First 1
$log += "scroll target: " + $(if($qvp){"$($qvp.B.X1),$($qvp.B.Y1)-$($qvp.B.X2),$($qvp.B.Y2)"}else{"NONE"})
if ($qvp) {
    $cx = [int](($qvp.B.X1 + $qvp.B.X2)/2); $cy = [int](($qvp.B.Y1 + $qvp.B.Y2)/2)
    A "input swipe $cx $cy $cx $($cy - 50) 250" | Out-Null
    Start-Sleep -Seconds 1
    Read-Deep "u1c_d_after_scroll1" | Out-Null
    A "input swipe $cx $cy $cx $($cy - 50) 250" | Out-Null
    Start-Sleep -Seconds 1
    Read-Deep "u1c_d_after_scroll2" | Out-Null
}
$log | Set-Content -Encoding UTF8 "$d\u1c_deep.log"
"DEEP DONE"
