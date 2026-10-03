$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$log=@()
$patR='Remo'+'ve'
$long = "Please explain in great detail how Android window insets propagation works across nested layouts"

function Rec { param([string]$tag)
    $ime = Get-ImeState
    $path = Dump-UI $tag
    $m = Measure-Frame $path
    $n = Get-Nodes $path
    $sN=($n|Where-Object{$_.Text -eq 'Steer'}).Count
    $xN=($n|Where-Object{$_.Text -eq $patR -or $_.Desc -eq $patR}).Count
    $s="$tag | imeShown=$($ime.Shown) | tlBox=$($m.timeline) tlH=$($m.tlH) | qb=$($m.queueBar) | ov=$($m.overlap) | READABLE=$($m.readable) | steerN=$sN b=$($m.steer.b) h=$($m.steer.h) clk=$($m.steer.clk) | xN=$xN b=$($m.xmark.b) h=$($m.xmark.h) clk=$($m.xmark.clk) | qH=$($m.qH) | etH=$($m.etH)"
    $script:log += $s; Write-Output $s
}
function Focus-Composer {
    $n = Get-Nodes (Dump-UI ("fc" + (Get-Random)))
    $et = $n | Where-Object { $_.Class -eq 'android.widget.EditText' } | Select-Object -First 1
    if ($et) { A "input tap $([int](($et.B.X1+$et.B.X2)/2)) $([int](($et.B.Y1+$et.B.Y2)/2))" | Out-Null; Start-Sleep -Milliseconds 900 }
}

$log += "wm BEFORE: " + ((A "wm size") -join " | ") + " || " + ((A "wm density") -join " | ")
Focus-Composer
Start-Sleep -Seconds 1
$ime0 = Get-ImeState
$log += "mInputShown after composer tap = $($ime0.Shown)"

if ($ime0.Shown) {
    Rec "ext_ok_1line_1" | Out-Null
    Rec "ext_ok_1line_2" | Out-Null
    Rec "ext_ok_1line_3" | Out-Null
} else {
    # retry once
    Focus-Composer; Start-Sleep -Seconds 2
    $ime1 = Get-ImeState
    $log += "mInputShown retry = $($ime1.Shown)"
    if ($ime1.Shown) { Rec "ext_ok_1line_1" | Out-Null; Rec "ext_ok_1line_2" | Out-Null; Rec "ext_ok_1line_3" | Out-Null }
}

# MANDATORY RESET
$log += "== RESET =="
A "wm size reset" | Out-Null
A "wm density reset" | Out-Null
Start-Sleep -Seconds 4
$log += "wm size AFTER: " + ((A "wm size") -join " | ")
$log += "wm density AFTER: " + ((A "wm density") -join " | ")
$log | Set-Content -Encoding UTF8 "$d\u1c_extwin2.log"
Write-Output "DONE"
