$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$log=@(); $g=0
$patR='Remo'+'ve'
$long = "Please explain in great detail how Android window insets propagation works across nested layouts"
$OUT="$d\u1c_ext.csv"
"tag,imeShown,tlBox,tlH,qbY,overlap,readable,steerB,steerH,steerClk,xB,xH,xClk,qH,etH" | Set-Content -Encoding UTF8 $OUT

function Rec { param([string]$tag)
    $ime = Get-ImeState
    $path = Dump-UI $tag
    $m = Measure-Frame $path
    $n = Get-Nodes $path
    $sN=($n|Where-Object{$_.Text -eq 'Steer'}).Count
    $xN=($n|Where-Object{$_.Text -eq $patR -or $_.Desc -eq $patR}).Count
    $line = "{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},{13},{14},{15}" -f $tag,$ime.Shown,$m.timeline,$m.tlH,$m.queueBar,$m.overlap,$m.readable,
        $(if($m.steer){$m.steer.b}),$(if($m.steer){$m.steer.h}),$(if($m.steer){$m.steer.clk}),
        $(if($m.xmark){$m.xmark.b}),$(if($m.xmark){$m.xmark.h}),$(if($m.xmark){$m.xmark.clk}),$m.qH,$m.etH
    Add-Content -Encoding UTF8 $OUT $line
    $s="$tag | ime=$($ime.Shown) | tlBox=$($m.timeline) tlH=$($m.tlH) | qb=$($m.queueBar) | ov=$($m.overlap) | READABLE=$($m.readable) | steerN=$sN h=$($m.steer.h) clk=$($m.steer.clk) | xN=$xN h=$($m.xmark.h) clk=$($m.xmark.clk) | qH=$($m.qH) | etH=$($m.etH)"
    $script:log += $s; Write-Output $s
}
function Nodes { Get-Nodes (Dump-UI ("e" + (++$g))) }
function Focus-Clear {
    $n = Nodes
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { A "input tap $([int](($et.B.X1+$et.B.X2)/2)) $([int](($et.B.Y1+$et.B.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 200 }
    A "input keycombination 113 29" | Out-Null; A "input keyevent 67" | Out-Null; Start-Sleep -Milliseconds 100
}
function TypeT { param([string]$t) if($t){ Type-Chunked $t; Start-Sleep -Milliseconds 150 } }
function Submit { param([string]$t)
    Focus-Clear; TypeT $t
    $n = Nodes
    $snd = $n | Where-Object { $_.Desc -eq 'Send' } | Select-Object -First 1
    if ($snd) { A "input tap $([int](($snd.B.X1+$snd.B.X2)/2)) $([int](($snd.B.Y1+$snd.B.Y2)/2))" | Out-Null } else { A "input keyevent 66" | Out-Null }
    Start-Sleep -Milliseconds 350
}

# ---- 1. try to get a running turn + queue chip, then depth ----
$log += "focus: " + (Get-Focus)
$tn = Nodes
$stop = ($tn | Where-Object { $_.Desc -eq 'Stop' }).Count
$log += "stop=$stop"
if ($stop -eq 0) {
    $log += "starting long turn"
    Submit "Count from 1 to 500 slowly with a detailed sentence for each number"
    Start-Sleep -Seconds 3
    $tn2 = Nodes
    $stop = ($tn2 | Where-Object { $_.Desc -eq 'Stop' }).Count
    $log += "stop after submit=$stop"
}
if ($stop -gt 0) {
    Focus-Clear; TypeT "toggle queue"
    $cn = Nodes
    $chip = $cn | Where-Object { $_.Text -eq 'Queue' -and $_.B -and $_.B.Y1 -gt 200 } | Select-Object -First 1
    $log += "chip: " + $(if($chip){"$($chip.B.X1),$($chip.B.Y1)-$($chip.B.X2)"}else{"NOT FOUND"})
    if ($chip) { A "input tap $([int](($chip.B.X1+$chip.B.X2)/2)) $([int](($chip.B.Y1+$chip.B.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 400 }
    for ($q=1; $q -le 12; $q++) { Submit "Queued depth item number $q" }
    Focus-Clear; TypeT ""
    Rec "ext_depth_1line" | Out-Null
    Rec "ext_depth_1line_b" | Out-Null
    Focus-Clear; TypeT $long
    Rec "ext_depth_2line" | Out-Null
    # scroll inside queue
    $qn = Nodes
    $qvp = $qn | Where-Object { $_.Scroll -and $_.W -gt 800 -and $_.B -and $_.B.Y1 -gt 150 } | Sort-Object { $_.B.Y1 } | Select-Object -First 1
    $log += "scroll target: " + $(if($qvp){"$($qvp.B.X1),$($qvp.B.Y1)-$($qvp.B.X2),$($qvp.B.Y2)"}else{"NONE"})
    if ($qvp) {
        $cx=[int](($qvp.B.X1+$qvp.B.X2)/2); $cy=[int](($qvp.B.Y1+$qvp.B.Y2)/2)
        A "input swipe $cx $cy $cx $($cy-40) 250" | Out-Null; Start-Sleep -Seconds 1
        Rec "ext_depth_scrolled" | Out-Null
    }
}

# ---- 2. extreme window ----
$log += "== extreme 1366x600 =="
A "wm size 1366x600" | Out-Null; A "wm density 160" | Out-Null; Start-Sleep -Seconds 4
$log += "wm size: " + ((A "wm size") -join " ")
$log += "wm density: " + ((A "wm density") -join " ")
$log += "focus: " + (Get-Focus)
Focus-Clear; TypeT ""
Rec "extwin_1line_1" | Out-Null
Rec "extwin_1line_2" | Out-Null
Rec "extwin_1line_3" | Out-Null
Focus-Clear; TypeT $long
Rec "extwin_2line_1" | Out-Null
Rec "extwin_2line_2" | Out-Null
Rec "extwin_2line_3" | Out-Null

# ---- 3. MANDATORY reset ----
$log += "== RESET =="
A "wm size reset" | Out-Null; A "wm density reset" | Out-Null; Start-Sleep -Seconds 4
$log += "wm size AFTER: " + ((A "wm size") -join " ")
$log += "wm density AFTER: " + ((A "wm density") -join " ")
Focus-Clear; TypeT ""
Rec "after_reset" | Out-Null

$log | Set-Content -Encoding UTF8 "$d\u1c_ext.log"
Write-Output "EXT DONE"
