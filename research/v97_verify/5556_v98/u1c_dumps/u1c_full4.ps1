$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$OUT="$d\u1c_frames.csv"
"iter,mode,frame,imeShown,timelineBox,tlH,qbY,overlap,readable,steerB,steerH,steerClk,xB,xH,xClk,qH,etH" | Set-Content -Encoding UTF8 $OUT
$log=@(); $g=0
$patR='Remo'+'ve'
$long = "Please explain in great detail how Android window insets propagation works across nested layouts"

function Nodes { Get-Nodes (Dump-UI ("t" + (++$g))) }

function Focus-Composer {
    $n = Nodes
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { A "input tap $([int](($et.B.X1+$et.B.X2)/2)) $([int](($et.B.Y1+$et.B.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 300 }
    A "input keycombination 113 29" | Out-Null
    A "input keyevent 67" | Out-Null
    Start-Sleep -Milliseconds 150
}
function TypeText { param([string]$t) if($t){ Type-Chunked $t; Start-Sleep -Milliseconds 300 } }
# tap Send with verification + retry
function Do-Send {
    for ($k=0; $k -lt 4; $k++) {
        $n = Nodes
        $snd = $n | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
        if (-not $snd) { Start-Sleep -Milliseconds 300; continue }
        $cx=[int](($snd.B.X1+$snd.B.X2)/2); $cy=[int](($snd.B.Y1+$snd.B.Y2)/2)
        A "input tap $cx $cy" | Out-Null
        Start-Sleep -Milliseconds 700
        $n2 = Nodes
        $stop = ($n2 | Where-Object { $_.Desc -eq 'Stop' }).Count
        $steer = ($n2 | Where-Object { $_.Text -eq 'Steer' }).Count
        if ($stop -gt 0 -or $steer -gt 0) { return $true }
    }
    return $false
}
function Start-Turn {
    Focus-Composer
    TypeText "Count from 1 to 400 slowly with a detailed sentence for every single number"
    $ok = Do-Send
    Start-Sleep -Seconds 2
    $script:log += "  Start-Turn sent=$ok"
    $ok
}
function Queue-Items { param([int]$cnt,[string]$prefix)
    for ($q=1; $q -le $cnt; $q++) {
        Focus-Composer
        TypeText "$prefix $q"
        Do-Send | Out-Null
        Start-Sleep -Milliseconds 250
    }
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

for ($i=1; $i -le 4; $i++) {
    Write-Output "===== ITER $i ====="
    Start-Turn | Out-Null
    # toggle queue chip
    Focus-Composer
    TypeText "toggle queue"
    $n = Nodes
    $chip = Find-Exact $n "Queue" 246 320
    Write-Output "chip=$($chip -ne $null)"
    if ($chip) { A "input tap $([int](($chip.B.X1+$chip.B.X2)/2)) $([int](($chip.B.Y1+$chip.B.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 500 }
    Queue-Items 3 "Queued prompt number"
    Focus-Composer; TypeText ""
    for ($f=1; $f -le 3; $f++) { Read-Frame $i "1line" $f }
    Focus-Composer; TypeText $long
    for ($f=1; $f -le 3; $f++) { Read-Frame $i "2line" $f }
}
$log | Set-Content -Encoding UTF8 "$d\u1c_loop.log"
Write-Output "LOOP DONE"
