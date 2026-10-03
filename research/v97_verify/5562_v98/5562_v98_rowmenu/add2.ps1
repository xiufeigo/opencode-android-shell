# add2.ps1 — full add flow with verification at each step. Usage: .\add2.ps1 -Target "ssh -p 2222 sshtest@10.255.255.2"
param([Parameter(Mandatory=$true)][string]$Target)
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5562"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\5562_v98_rowmenu"
function Dump($t){ $r="/sdcard/x_$t.xml"; $l=Join-Path $dir "x_$t.xml"
  & $adb -s $dev shell uiautomator dump --compressed $r | Out-Null
  & $adb -s $dev pull $r $l | Out-Null; & $adb -s $dev shell "rm -f $r" | Out-Null
  [xml]$x = Get-Content -LiteralPath $l -Raw -Encoding UTF8; return $x.SelectNodes("//node") }
function Txt($n){ $t=$n.GetAttribute("text"); if(-not $t){$t=$n.GetAttribute("content-desc")}; $t }
function Ctr($b){ if($b -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]'){ $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]; return "$([int](($x1+$x2)/2)),$([int](($y1+$y2)/2))" }; "" }
function Tap($c){ & $adb -s $dev shell input tap ($c.Replace(",", " ")); }

# 1 palette -> settings
Tap "116,54"; Start-Sleep -Seconds 2
& $adb -s $dev shell input text "settings"; Start-Sleep -Seconds 2
$n = Dump "a"; $h = $n | Where-Object { (Txt $_) -eq "Open settings" } | Select-Object -First 1
if(-not $h){ Write-Output "S1 FAIL"; exit 1 }
$wrap = $h.ParentNode; $wc = Ctr $wrap.GetAttribute("bounds")
if(-not $wc){ $wc = Ctr $h.GetAttribute("bounds") }
Tap $wc; Start-Sleep -Seconds 3
# 2 Servers tab
$n = Dump "b"; $t = $n | Where-Object { (Txt $_) -eq "Servers" } | Select-Object -First 1
if(-not $t){ Write-Output "S2 FAIL"; exit 1 }
Tap (Ctr $t.GetAttribute("bounds")); Start-Sleep -Seconds 2
# 3 Add SSH server
$n = Dump "c"; $b3 = $n | Where-Object { (Txt $_) -eq "Add SSH server" } | Select-Object -First 1
if(-not $b3){ Write-Output "S3 FAIL"; exit 1 }
Tap (Ctr $b3.GetAttribute("bounds")); Start-Sleep -Seconds 2
# 4 target field = rightmost EditText with x1>=458
$n = Dump "d"
$cand = $n | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" -and $_.GetAttribute("bounds") -match '^\[(\d+),' -and [int]$Matches[1] -ge 458 }
if($cand.Count -eq 0){ Write-Output "S4 FAIL no field"; exit 1 }
$fc = Ctr $cand[0].GetAttribute("bounds"); Write-Output "field @ $fc"
Tap $fc; Start-Sleep -Milliseconds 900
foreach ($p in ($Target -split ' ')) { & $adb -s $dev shell input text $p; & $adb -s $dev shell input keyevent 62 }
Start-Sleep -Milliseconds 500
& $adb -s $dev shell input keyevent 111; Start-Sleep -Seconds 1
$n = Dump "e"
$chk = $n | Where-Object { (Txt $_) -like "*10.255*" } | Select-Object -First 1
if(-not $chk){ Write-Output "S5 FAIL target not in dump"; exit 1 }
Write-Output ("target ok: " + (Txt $chk))
# 5 page1 Add server
$a1 = $n | Where-Object { (Txt $_) -eq "Add server" } | Select-Object -First 1
if(-not $a1){ Write-Output "S6 FAIL no add p1"; exit 1 }
Tap (Ctr $a1.GetAttribute("bounds")); Start-Sleep -Seconds 2
# 6 password
$n = Dump "f"
$pe = $n | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" -and $_.GetAttribute("password") -eq "true" } | Select-Object -First 1
if($pe){ $pc = Ctr $pe.GetAttribute("bounds"); Write-Output "pw @ $pc"
  Tap $pc; Start-Sleep -Milliseconds 900
  & $adb -s $dev shell input text "e2echk5562m"; Start-Sleep -Milliseconds 500
  & $adb -s $dev shell input keyevent 111; Start-Sleep -Milliseconds 800 }
$n = Dump "g"
$a2 = $n | Where-Object { (Txt $_) -eq "Add server" } | Select-Object -First 1
if($a2){ Tap (Ctr $a2.GetAttribute("bounds")) }
Start-Sleep -Seconds 3
Write-Output "ADD2 DONE"
