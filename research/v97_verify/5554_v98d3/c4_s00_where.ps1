. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir
$x = Cap "c4_s00_where"
Say "XML=$x"
$vp = Viewport
if ($vp) { Say ("VIEWPORT=[{0},{1}][{2},{3}]" -f $vp['l'],$vp['t'],$vp['r'],$vp['b']) } else { Say "VIEWPORT=null" }
DumpT "s00_where"
$null = IsANR
