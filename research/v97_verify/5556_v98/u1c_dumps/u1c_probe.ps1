$ErrorActionPreference='Stop'
$d="C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98\u1c_dumps"
. "$d\u1c_lib.ps1"
$patR='Remo'+'ve'
$log=@()
function Snap { param([string]$t)
    $n=Get-Nodes (Dump-UI $t)
    $stop=($n|Where-Object{$_.Desc -eq 'Stop'}).Count
    $send=($n|Where-Object{$_.Desc -eq 'Send'}).Count
    $steer=($n|Where-Object{$_.Text -eq 'Steer'}).Count
    $x=($n|Where-Object{$_.Text -eq $patR -or $_.Desc -eq $patR}).Count
    $m=Measure-Frame (Join-Path $d "u1c_$t.xml")
    $s="$t stop=$stop send=$send steer=$steer x=$x tl=$($m.tlH) READABLE=$($m.readable) etH=$($m.etH)"
    $script:log+=$s; $s
}
Snap "probe1"
Snap "probe2"
$log | Set-Content -Encoding UTF8 "$d\u1c_probe.log"
