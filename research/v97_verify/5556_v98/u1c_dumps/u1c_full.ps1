$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$OUT="$d\u1c_frames.csv"
"iter,mode,frame,imeShown,timeline,timelineBox,tlH,queueBarY,overlap,readable,steer,steerH,steerClk,xmark,xmarkH,xmarkClk,qvp,qvpH,qlineH,etH" | Set-Content -Encoding UTF8 $OUT
$log=@()

function Read-Frame {
    param([int]$iter,[string]$mode,[int]$frame)
    $ime = Get-ImeState
    $tag = "u1c_i{0}_{1}_f{2}" -f $iter,$mode,$frame
    $p = Dump-UI $tag
    $m = Measure-Frame $p
    $nodes = Get-Nodes $p
    $tlbox = $null
    if ($m.timeline) {
        $parts = $m.timeline -split ' - '
        $y1 = ($parts[0] -split ',')[1]; $y2 = ($parts[1] -split ',')[1]
        $cand = $nodes | Where-Object { $_.B -and $_.B.Y1 -eq [int]$y1 -and $_.B.Y2 -eq [int]$y2 } | Sort-Object { $_.W } -Descending | Select-Object -First 1
        if ($cand) { $tlbox = "$($cand.B.X1),$($cand.B.Y1)-$($cand.B.X2),$($cand.B.Y2)" }
    }
    $line = "{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},{13},{14},{15},{16},{17},{18},{19}" -f `
        $iter,$mode,$frame,$ime.Shown,$m.timeline,$tlbox,$m.tlH,$m.queueBar,$m.overlap,$m.readable,
        $(if($m.steer){$m.steer.b}),$(if($m.steer){$m.steer.h}),$(if($m.steer){$m.steer.clk}),
        $(if($m.xmark){$m.xmark.b}),$(if($m.xmark){$m.xmark.h}),$(if($m.xmark){$m.xmark.clk}),
        $m.queueVP,$m.qH,$m.etH,''
    Add-Content -Encoding UTF8 $OUT $line
    $s = "[$iter/$mode/$frame] ime=$($ime.Shown) tl=$($m.tlH) qb=$($m.queueBar) ov=$($m.overlap) READABLE=$($m.readable) steer=$($m.steer.h)/$($m.steer.clk) x=$($m.xmark.h)/$($m.xmark.clk) qlineH=$($m.qH) etH=$($m.etH)"
    $script:log += $s; $s
}

function Turn-Running {
    $n = Get-Nodes (Dump-UI ("u1c_st_" + [guid]::NewGuid().ToString().Substring(0,6)))
    ($n | Where-Object { $_.Desc -eq 'Stop' }).Count -gt 0
}

# ---------- 1. VERY long-running turn ----------
$log += "== long turn =="
$n = Get-Nodes (Dump-UI "u1c_s0")
$et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 700
Type-Chunked "Count from 1 to 200 slowly"
Start-Sleep -Milliseconds 600
$n2 = Get-Nodes (Dump-UI "u1c_s1")
$snd = $n2 | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
Tap-Node $snd | Out-Null
Start-Sleep -Seconds 4
$log += "turn running: " + (Turn-Running)

# ---------- 2. toggle Queue chip + 3 queued prompts ----------
$n = Get-Nodes (Dump-UI "u1c_s2")
$et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 600
Type-Chunked "toggle queue"
Start-Sleep -Seconds 1
$n = Get-Nodes (Dump-UI "u1c_s3")
$chip = Find-Exact $n "Queue" 246 298
$log += "chip: " + $(if($chip){"$($chip.B.X1),$($chip.B.Y1)-$($chip.B.X2),$($chip.B.Y2)"}else{"NOT FOUND"})
if ($chip) { Tap-Node $chip | Out-Null; Start-Sleep -Milliseconds 800 }

for ($i=1; $i -le 3; $i++) {
    $n = Get-Nodes (Dump-UI "u1c_sq$i")
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 500 }
    A "input keycombination 113 29" | Out-Null; A "input keyevent 67" | Out-Null
    Start-Sleep -Milliseconds 200
    Type-Chunked "Queued prompt number $i for U1C depth test"
    Start-Sleep -Milliseconds 500
    $n2 = Get-Nodes (Dump-UI "u1c_ss$i")
    $snd = $n2 | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
    if ($snd) { Tap-Node $snd | Out-Null; $log += "queued #$i" } else { $log += "NO SEND q$i" }
    Start-Sleep -Milliseconds 600
}

# ---------- 3. the 4x(1line x3 + 2line x3) loop ----------
$long = "Please explain in great detail how Android window insets propagation works across nested layouts"
for ($i=1; $i -le 4; $i++) {
    $log += "===== ITER $i ====="
    # refresh queue if drained
    if (-not (Turn-Running)) {
        $log += "turn ended at iter $i -> re-trigger"
        $n = Get-Nodes (Dump-UI "u1c_rt$i")
        $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
        if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 500 }
        A "input keycombination 113 29" | Out-Null; A "input keyevent 67" | Out-Null
        Type-Chunked "Count from 1 to 200 slowly"
        Start-Sleep -Milliseconds 500
        $n2 = Get-Nodes (Dump-UI "u1c_rs$i")
        $snd = $n2 | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
        if ($snd) { Tap-Node $snd | Out-Null; Start-Sleep -Seconds 3 }
        # re-queue
        $n = Get-Nodes (Dump-UI "u1c_rq$i")
        $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
        if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 500 }
        Type-Chunked "toggle queue"
        Start-Sleep -Milliseconds 800
        $n = Get-Nodes (Dump-UI "u1c_rqc$i")
        $chip = Find-Exact $n "Queue" 246 298
        if ($chip) { Tap-Node $chip | Out-Null; Start-Sleep -Milliseconds 600 }
        for ($q=1; $q -le 3; $q++) {
            $n = Get-Nodes (Dump-UI "u1c_rqs$($i)$q")
            $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
            if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 400 }
            A "input keycombination 113 29" | Out-Null; A "input keyevent 67" | Out-Null
            Type-Chunked "Queued refill $q"
            Start-Sleep -Milliseconds 400
            $n2 = Get-Nodes (Dump-UI "u1c_rqd$($i)$q")
            $snd = $n2 | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
            if ($snd) { Tap-Node $snd | Out-Null }
            Start-Sleep -Milliseconds 500
        }
        $log += "refill done"
    }

    # 1-LINE
    $n = Get-Nodes (Dump-UI "u1c_p$i")
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 400 }
    A "input keycombination 113 29" | Out-Null; A "input keyevent 67" | Out-Null
    Start-Sleep -Milliseconds 300
    for ($f=1; $f -le 3; $f++) { Read-Frame $i "1line" $f }

    # 2-LINE
    $n = Get-Nodes (Dump-UI "u1c_p2$i")
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 400 }
    Type-Chunked $long
    Start-Sleep -Milliseconds 600
    for ($f=1; $f -le 3; $f++) { Read-Frame $i "2line" $f }
}
$log | Set-Content -Encoding UTF8 "$d\u1c_loop.log"
"LOOP DONE"
