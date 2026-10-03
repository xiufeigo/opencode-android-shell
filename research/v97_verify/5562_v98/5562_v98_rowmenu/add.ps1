# add.ps1 — add an SSH entry via the 2-step form. Usage: .\add.ps1 -Target "ssh -p 2222 sshtest@10.255.255.1" -Password "e2echk5562m"
param(
  [Parameter(Mandatory=$true)][string]$Target,
  [string]$Password = "e2echk5562m"
)
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5562"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\5562_v98_rowmenu"

function Dump($tag) {
  $r = "/sdcard/ad_$tag.xml"; $l = Join-Path $dir "ad_$tag.xml"
  & $adb -s $dev shell uiautomator dump --compressed $r | Out-Null
  & $adb -s $dev pull $r $l | Out-Null
  & $adb -s $dev shell "rm -f $r" | Out-Null
  [xml]$x = Get-Content -LiteralPath $l -Raw -Encoding UTF8
  return $x.SelectNodes("//node")
}
function Txt($n) { $t = $n.GetAttribute("text"); if (-not $t) { $t = $n.GetAttribute("content-desc") }; return $t }
function Ctr($b) {
  if ($b -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
    $x1=[int]$Matches[1];$y1=[int]$Matches[2];$x2=[int]$Matches[3];$y2=[int]$Matches[4]
    return "$([int](($x1+$x2)/2)),$([int](($y1+$y2)/2))"
  }; return ""
}
# type a string with keyevent 62 for spaces
function TypeText($s) {
  foreach ($part in $s -split ' ') {
    if ($part -ne "") { & $adb -s $dev shell input text $part }
    & $adb -s $dev shell input keyevent 62
  }
}

# ensure we are on the Servers tab with panel open
$n = Dump "pre"
if (-not ($n | Where-Object { (Txt $_) -eq "Add SSH server" })) { Write-Output "ADD FAIL: not on Servers panel"; exit 1 }

# 1. open form
$btn = $n | Where-Object { (Txt $_) -eq "Add SSH server" -and $_.GetAttribute("clickable") -ne "true" } | Select-Object -First 1
$bc = Ctr $btn.GetAttribute("bounds")
& $adb -s $dev shell input tap ($bc.Replace(",", " ")); Start-Sleep -Seconds 2

# 2. fill target field (page 1)
$n = Dump "p1"
$et = $n | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" -and $_.GetAttribute("bounds") -match '^\[(\d+),' -and [int]$Matches[1] -ge 458 } | Select-Object -First 1
$ec = Ctr $et.GetAttribute("bounds")
Write-Output "target field @ $ec"
& $adb -s $dev shell input tap ($ec.Replace(",", " ")); Start-Sleep -Milliseconds 800
TypeText $Target
Start-Sleep -Milliseconds 600
& $adb -s $dev shell input keyevent 111; Start-Sleep -Milliseconds 800

# 3. click page-1 Add server
$n = Dump "p1b"
$as = $n | Where-Object { (Txt $_) -eq "Add server" } | Select-Object -First 1
if (-not $as) { Write-Output "ADD FAIL: no Add server on p1"; exit 1 }
$ac = Ctr $as.GetAttribute("bounds")
& $adb -s $dev shell input tap ($ac.Replace(",", " ")); Start-Sleep -Seconds 2

# 4. password field on page 2
$n = Dump "p2"
$ets = $n | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" }
Write-Output ("p2 edittexts=" + $ets.Count)
if ($ets.Count -gt 0) {
  $pe = Ctr $ets[0].GetAttribute("bounds")
  & $adb -s $dev shell input tap ($pe.Replace(",", " ")); Start-Sleep -Milliseconds 800
  & $adb -s $dev shell input text $Password
  Start-Sleep -Milliseconds 500
  & $adb -s $dev shell input keyevent 66; Start-Sleep -Seconds 2
} else {
  $as2 = $n | Where-Object { (Txt $_) -eq "Add server" } | Select-Object -First 1
  $ac2 = Ctr $as2.GetAttribute("bounds")
  & $adb -s $dev shell input tap ($ac2.Replace(",", " ")); Start-Sleep -Seconds 2
}
Write-Output "ADD DONE"
