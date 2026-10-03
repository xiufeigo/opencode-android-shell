. "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98d3\c4lib.ps1"
InitDir
function Ime { $o = & $global:ADB -s emulator-5554 shell "dumpsys input_method | grep -E 'mInputShown' | head -1"; return ($o -join ' ').Trim() }
function ETs { foreach ($n in (DumpET)) { $b = Bnd $n; Say ("   ET [{0},{1}][{2},{3}] foc={4} | '{5}'" -f $b['l'],$b['t'],$b['r'],$b['b'],$n.GetAttribute("focused"),(TxtOf $n)) } }
Say "IME=" + (Ime); Say "-- ETs now --"; ETs
foreach ($k in 61,20,19,66) {
  Say "== try keyevent $k =="
  Sh "input keyevent $k"; Start-Sleep -Milliseconds 900
  Say ("   IME=" + (Ime))
  ETs
  $x = Cap ("c4_imekey_{0}" -f $k)
  $btn = BtnState "Add server"
  Say ("   BTN " + $btn)
  if ((Ime) -match 'false') { Say "   *** IME CLOSED with keyevent $k ***"; break }
}
