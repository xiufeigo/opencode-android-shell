$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$OUT="$d\u1c_frames.csv"
"iter,mode,frame,imeShown,timelineBox,tlH,qbY,overlap,readable,steerB,steerH,steerClk,xB,xH,xClk,qH,etH" | Set-Content -Encoding UTF8 $OUT
$log=@(); $g=0
$long = "Please explain in great detail how Android window insets propagation works across nested layouts"
$TURN = "Count from 1 to 900 slowly with a detailed sentence for each number"

function Nodes { Get-Nodes (Dump-UI ("t" + (++$g))) }
function Focus-Clear {
    $n = Nodes
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { A "input tap $([int](($et.B.X1+$et.B.X2)/2)) $([int](($et.B.Y1+$et.B.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 220 }
    A "input keycombination 113 29" | Out-Null
    A "input keyevent 67" | Out-Null
    Start-Sleep -Milliseconds 100
}
function TypeT { param([string]$t) if($t){ Type-Chunked $t; Start-Sleep -Milliseconds 200 } }
function Submit { param([string]$t)
    Focus-Clear; TypeT $t
    $n = Nodes
    $snd = $n | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
    if ($snd) { A "input tap $([int](($snd.B.X1+$snd.B.X2)/2)) $([int](($snd.B.Y1+$snd.B.Y2)/2))" | Out-Null }
    else { A "input keyevent 66" | Out-Null }
    Start-Sleep -Milliseconds 450
}
# Start/refresh a long-running turn; returns true if Stop button seen
function Turn-Running {
    $n = Nodes
    ($n | Where-Object { $_.Desc -eq 'Stop' }).Count -gt 0
}
function Ensure-Turn {
    if (Turn-Running) { return $true }
    Submit $TURN
    Start-Sleep -Seconds 2
    return (Turn-Running)
}
function Read-Frame { param([int]$it,[string]$mode,[int]$f)
    $ime = Get-ImeState
    $tag = "u1c_i{0}_{1}_f{2}" -f $it,$mode,$f
    $p = Dump-UI $tag
    $m = Measure-Frame $p
    $line = "{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},{13},{14},{15},{16}" -f `
        $it,$mode,$f,$ime.Shown,$m.timeline,$m.tlH,$m.queueBar,$m.overlap,$m.readable,
        $(if($m.steer){$m.steer.b}),$(if($m.steer){$m.steer.h}),$(if($m.steer){$m.steer.clk}),
        $(if($m.xmark){$m.xmark.b}),$(if($m.xmark){$m.xmark.h}),$(if($m.xmark){$m.xmark.clk}),
        $m.qH,$m.etH
    Add-Content -Encoding UTF8 $OUT $line
    $s = "[$it/$mode/$f] ime=$($ime.Shown) tl=$($m.tlH) qb=$($m.queueBar) ov=$($m.overlap) READABLE=$($m.readable) steer=$($m.steer.h)/$($m.steer.clk) x=$($m.xmark.h)/$($m.xmark.clk) qH=$($m.qH) etH=$($m.etH)"
    $script:log += $s; Write-Output $s
}
# toggle queue chip: exact text 'Queue' anywhere in the lower tool area
function Toggle-Queue {
    Focus-Clear; TypeT "toggle queue"
    $n = Nodes
    $chip = $n | Where-Object { $_.Text -eq 'Queue' -and $_.B -and $_.B.Y1 -gt 200 } | Select-Object -First 1
    if ($chip) {
        $script:log += "  chip at $($chip.B.X1),$($chip.B.Y1)-$($chip.B.X2),$($chip.B.Y2)"
        A "input tap $([int](($chip.B.X1+$chip.B.X2)/2)) $([int](($chip.B.Y1+$chip.B.Y2)/2))" | Out-Null
        Start-Sleep -Milliseconds 400
        return $true
    }
    $script:log += "  CHIP NOT FOUND. lower-region dump:"
    $n | Where-Object { $_.B -and $_.B.Y1 -gt 150 -and ($_.Text -or $_.Desc) } | ForEach-Object {
        $script:log += "    '$($_.Text)' | '$($_.Desc)' | $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)"
    }
    return $false
}

# ---------- main loop ----------
Ensure-Turn | Out-Null
Start-Sleep -Seconds 2
Write-Output "== DIAG: turn running = $(Turn-Running) =="
# diagnostic snapshot of tool row while running
$dn = Nodes
$script:log += "== DIAG tool row =="
$dn | Where-Object { $_.B -and $_.B.Y1 -gt 150 -and ($_.Text -or $_.Desc) } | ForEach-Object {
    $script:log += "   '$($_.Text)' | '$($_.Desc)' | $($_.B.X1),$($_.B.Y1)-$($_.B.X2),$($_.B.Y2)"
}

for ($i=1; $i -le 4; $i++) {
    Write-Output "===== ITER $i ====="
    $run = Ensure-Turn
    Write-Output "turn=$run"
    $ok = Toggle-Queue
    Write-Output "chipToggled=$ok"
    for ($q=1; $q -le 3; $q++) { Submit "Queued prompt number $q for U1C depth test" }
    Focus-Clear; TypeT ""
    for ($f=1; $f -le 3; $f++) { Read-Frame $i "1line" $f }
    Focus-Clear; TypeT $long
    for ($f=1; $f -le 3; $f++) { Read-Frame $i "2line" $f }
}
$log | Set-Content -Encoding UTF8 "$d\u1c_loop.log"
Write-Output "LOOP DONE"
