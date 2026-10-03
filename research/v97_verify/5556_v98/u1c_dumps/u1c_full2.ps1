$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$OUT="$d\u1c_frames.csv"
"iter,mode,frame,imeShown,timelineBox,tlH,qbY,overlap,readable,steerB,steerH,steerClk,xB,xH,xClk,qH,etH" | Set-Content -Encoding UTF8 $OUT
$log=@()

# composer focus + clear + type
function Compose {
    param([string]$Text,[bool]$Clear=$true)
    $n = Get-Nodes (Dump-UI ("c_" + [guid]::NewGuid().ToString().Substring(0,5)))
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { A "input tap $([int](($et.B.X1+$et.B.X2)/2)) $([int](($et.B.Y1+$et.B.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 350 }
    if ($Clear) { A "input keycombination 113 29" | Out-Null; A "input keyevent 67" | Out-Null; Start-Sleep -Milliseconds 150 }
    if ($Text) { Type-Chunked $Text; Start-Sleep -Milliseconds 400 }
}
function Press-Send {
    $n = Get-Nodes (Dump-UI ("s_" + [guid]::NewGuid().ToString().Substring(0,5)))
    $snd = $n | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
    if ($snd) { A "input tap $([int](($snd.B.X1+$snd.B.X2)/2)) $([int](($snd.B.Y1+$snd.B.Y2)/2))" | Out-Null; return $true }
    return $false
}

function Read-Frame {
    param([int]$iter,[string]$mode,[int]$frame)
    $ime = Get-ImeState
    $tag = "u1c_i{0}_{1}_f{2}" -f $iter,$mode,$frame
    $p = Dump-UI $tag
    $m = Measure-Frame $p
    $line = "{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},{13},{14},{15},{16}" -f `
        $iter,$mode,$frame,$ime.Shown,$m.timeline,$m.tlH,$m.queueBar,$m.overlap,$m.readable,
        $(if($m.steer){$m.steer.b}),$(if($m.steer){$m.steer.h}),$(if($m.steer){$m.steer.clk}),
        $(if($m.xmark){$m.xmark.b}),$(if($m.xmark){$m.xmark.h}),$(if($m.xmark){$m.xmark.clk}),
        $m.qH,$m.etH
    Add-Content -Encoding UTF8 $OUT $line
    $s = "[$i/$mode/$frame] ime=$($ime.Shown) tl=$($m.tlH) qb=$($m.queueBar) ov=$($m.overlap) READABLE=$($m.readable) steer=$($m.steer.h)/$($m.steer.clk) x=$($m.xmark.h)/$($m.xmark.clk) qH=$($m.qH) etH=$($m.etH)"
    $script:log += $s; Write-Output $s
}

$long = "Please explain in great detail how Android window insets propagation works across nested layouts"

for ($i=1; $i -le 4; $i++) {
    $script:i = $i
    Write-Output "===== ITER $i : topping up queue ====="
    # a very long-running turn to keep the queue alive
    Compose "Count from 1 to 400 slowly with a detailed sentence for every single number" $true
    Press-Send | Out-Null
    Start-Sleep -Seconds 3
    # toggle queue chip
    Compose "toggle queue" $true
    $n = Get-Nodes (Dump-UI "u1c_chipx$i")
    $chip = Find-Exact $n "Queue" 246 298
    Write-Output "chip: $($chip -ne $null)"
    if ($chip) { A "input tap $([int](($chip.B.X1+$chip.B.X2)/2)) $([int](($chip.B.Y1+$chip.B.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 600 }
    # 3 queued prompts
    for ($q=1; $q -le 3; $q++) {
        Compose "Queued prompt number $q for U1C depth test" $true
        Press-Send | Out-Null
        Start-Sleep -Milliseconds 400
    }
    Write-Output "--- measuring 1-line ---"
    Compose "" $true
    for ($f=1; $f -le 3; $f++) { Read-Frame $i "1line" $f }
    Write-Output "--- measuring 2-line ---"
    Compose $long $true
    for ($f=1; $f -le 3; $f++) { Read-Frame $i "2line" $f }
}
$log | Set-Content -Encoding UTF8 "$d\u1c_loop.log"
Write-Output "LOOP DONE"
