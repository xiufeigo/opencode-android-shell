$ErrorActionPreference='Continue'
. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\_tools\u11_lib.ps1"
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$out="$d\_u11_ALL.txt"
$log=@()
$ime=ImeShown
$log += "IME_GATE=$ime"
if($ime -ne 'false'){ $log += "IME_NOT_CLOSED_ABORT" }
$log += "CONTAINER=" + (Container "u11_10_files")
$log += "ToTop=" + (ToTop "u11_20_base")
$ime2=ImeShown
$log += "IME_AFTER_TOTOP=$ime2"
$r=Traverse "u11_21_base" 16
$log += "VIEWPORT_MAX_PX=" + $r.viewport
$log += "STEPS=" + $r.steps
$log += "COUNT=" + $r.rows.Count
$log += "ROWS=" + (Names $r.rows)
$log | Out-File -FilePath $out -Encoding utf8
$log
