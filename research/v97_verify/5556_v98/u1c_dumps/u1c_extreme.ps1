$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$log=@()
$patR = 'Remo' + 've'
$long = "Please explain in great detail how Android window insets propagation works across nested layouts"

function Read-Ext {
    param([string]$tag)
    $ime = Get-ImeState
    $p = Dump-UI $tag
    $m = Measure-Frame $p
    $n = Get-Nodes $p
    $sN = ($n | Where-Object { $_.Text -eq 'Steer' -or $_.Desc -eq 'Steer' }).Count
    $xN = ($n | Where-Object { $_.Text -eq $patR -or $_.Desc -eq $patR }).Count
    $line = "$tag | ime=$($ime.Shown) | tl=$($m.timeline) tlH=$($m.tlH) | qb=$($m.queueBar) | ov=$($m.overlap) | READABLE=$($m.readable) | steer=$($m.steer.b) h=$($m.steer.h) clk=$($m.steer.clk) n=$sN | x=$($m.xmark.b) h=$($m.xmark.h) clk=$($m.xmark.clk) n=$xN | qvp=$($m.queueVP) qH=$($m.qH) | etH=$($m.etH)"
    $script:log += $line; $line
}

$log += "== BEFORE extreme window =="
Read-Ext "u1c_e_pre" | Out-Null

$log += "== applying 1366x600 + density 160 =="
A "wm size 1366x600" | Out-Null
A "wm density 160" | Out-Null
Start-Sleep -Seconds 4
$log += "wm size: " + ((A "wm size") -join " ")
$log += "wm density: " + ((A "wm density") -join " ")
$log += "focus: " + (Get-Focus)

Start-Sleep -Seconds 2
$log += "== extreme window frames =="
# 1-line
$n = Get-Nodes (Dump-UI "u1c_e_p1")
$et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 400 }
A "input keycombination 113 29" | Out-Null; A "input keyevent 67" | Out-Null
Start-Sleep -Milliseconds 300
Read-Ext "u1c_e_1line_1" | Out-Null
Read-Ext "u1c_e_1line_2" | Out-Null
Read-Ext "u1c_e_1line_3" | Out-Null

# 2-line
$n = Get-Nodes (Dump-UI "u1c_e_p2")
$et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
if ($et) { Tap-Node $et | Out-Null; Start-Sleep -Milliseconds 400 }
Type-Chunked $long
Start-Sleep -Milliseconds 600
Read-Ext "u1c_e_2line_1" | Out-Null
Read-Ext "u1c_e_2line_2" | Out-Null
Read-Ext "u1c_e_2line_3" | Out-Null

$log += "== resetting wm =="
A "wm size reset" | Out-Null
A "wm density reset" | Out-Null
Start-Sleep -Seconds 4
$log += "wm size AFTER reset: " + ((A "wm size") -join " ")
$log += "wm density AFTER reset: " + ((A "wm density") -join " ")
Start-Sleep -Seconds 2
$log += "focus after reset: " + (Get-Focus)
Read-Ext "u1c_e_after_reset" | Out-Null

$log | Set-Content -Encoding UTF8 "$d\u1c_extreme.log"
"EXTREME DONE"
