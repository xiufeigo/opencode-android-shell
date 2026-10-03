$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$OUT="$d\u1c_frames.csv"
"iter,mode,frame,imeShown,timeline,timelineBox,tlH,queueBar,overlap,readable,steer,steerH,steerClk,xmark,xmarkH,xmarkClk,qvp,qvpH,etH,etBounds" | Set-Content -Encoding UTF8 $OUT

function Clear-Composer {
    A "input keycombination 113 29" | Out-Null   # CTRL+A
    Start-Sleep -Milliseconds 200
    A "input keyevent 67" | Out-Null           # DEL
    Start-Sleep -Milliseconds 300
}

function Read-Frame {
    param([int]$iter,[string]$mode,[int]$frame)
    $ime = Get-ImeState
    $p = Dump-UI ("u1c_i{0}_{1}_f{2}" -f $iter,$mode,$frame)
    $m = Measure-Frame $p

    # full-width box sharing the timeline y-range
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
        $m.queueVP,$m.qvpH,$m.etH,$m.etB
    Add-Content -Encoding UTF8 $OUT $line
    "[$iter/$mode/$frame] ime=$($ime.Shown) tl=$($m.tlH) qb=$($m.queueBar) ov=$($m.overlap) READABLE=$($m.readable) steer=$($m.steer.h) x=$($m.xmark.h) qvpH=$($m.qvpH) etH=$($m.etH)"
}

$long = "Please explain in great detail how Android window insets propagation works across nested layouts"

for ($i=1; $i -le 4; $i++) {
    "===== ITERATION $i : 1-LINE ====="
    # ensure composer focused & empty
    $n = Get-Nodes (Dump-UI "u1c_i${i}_pre")
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 500 }
    Clear-Composer
    for ($f=1; $f -le 3; $f++) { Read-Frame $i "1line" $f }

    "===== ITERATION $i : 2-LINE ====="
    $n = Get-Nodes (Dump-UI "u1c_i${i}_pre2")
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 500 }
    Type-Chunked $long
    Start-Sleep -Milliseconds 600
    for ($f=1; $f -le 3; $f++) { Read-Frame $i "2line" $f }
}
"LOOP DONE"
