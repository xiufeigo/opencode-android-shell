$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$log=@()
$patR='Remo'+'ve'
function Rec { param([string]$tag)
    $ime = Get-ImeState
    $path = Dump-UI $tag
    $m = Measure-Frame $path
    $n = Get-Nodes $path
    $sN=($n|Where-Object{$_.Text -eq 'Steer'}).Count
    $xN=($n|Where-Object{$_.Text -eq $patR -or $_.Desc -eq $patR}).Count
    $s="$tag | ime=$($ime.Shown) | tlBox=$($m.timeline) tlH=$($m.tlH) | qb=$($m.queueBar) | ov=$($m.overlap) | READABLE=$($m.readable) | steerN=$sN b=$($m.steer.b) h=$($m.steer.h) clk=$($m.steer.clk) | xN=$xN b=$($m.xmark.b) h=$($m.xmark.h) clk=$($m.xmark.clk) | qH=$($m.qH) | etH=$($m.etH)"
    $script:log += $s; Write-Output $s
}
$log += "wm size: " + ((A "wm size") -join " ")
$log += "wm density: " + ((A "wm density") -join " ")
$log += "focus: " + (Get-Focus)
Rec "extwin_a" | Out-Null
Rec "extwin_b" | Out-Null
Rec "extwin_c" | Out-Null
$log | Set-Content -Encoding UTF8 "$d\u1c_extwin.log"
Write-Output "EXTWIN READ DONE"
