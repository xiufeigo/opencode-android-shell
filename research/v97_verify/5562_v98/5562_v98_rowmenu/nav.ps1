# nav.ps1 — open Settings -> Servers tab on emulator-5562, with verification
param([string]$ShotTag = "")
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = "emulator-5562"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5562_v98\5562_v98_rowmenu"

function Dump($tag) {
  $r = "/sdcard/nv_$tag.xml"; $l = Join-Path $dir "nv_$tag.xml"
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

# 1. open palette
& $adb -s $dev shell input tap 116 54; Start-Sleep -Seconds 2
& $adb -s $dev shell input text "settings"; Start-Sleep -Seconds 2
$n = Dump "pal"
$hit = $n | Where-Object { (Txt $_) -eq "Open settings" } | Select-Object -First 1
if (-not $hit) { Write-Output "NAV FAIL: Open settings not found"; exit 1 }
$row = $n | Where-Object { (Txt $_) -eq "" -and $_.GetAttribute("clickable") -eq "true" -and (Txt ($_.ParentNode)) -eq "" } | Select-Object -First 1
# the clickable row wrapping 'Open settings'
$wrap = $hit.ParentNode.ParentNode
$c = Ctr $wrap.GetAttribute("bounds")
if (-not $c) { $c = Ctr $hit.GetAttribute("bounds") }
Write-Output "tap Open settings @ $c"
& $adb -s $dev shell input tap $c.Replace(",", " "); Start-Sleep -Seconds 3

# 2. tap Servers tab
$n = Dump "set"
$tab = $n | Where-Object { (Txt $_) -eq "Servers" } | Select-Object -First 1
if (-not $tab) { Write-Output "NAV FAIL: Servers tab not found"; exit 1 }
$tc = Ctr $tab.GetAttribute("bounds")
Write-Output "tap Servers tab @ $tc"
& $adb -s $dev shell input tap $tc.Replace(",", " "); Start-Sleep -Seconds 2

if ($ShotTag -ne "") {
  & $adb -s $dev shell screencap -p "/sdcard/$ShotTag.png"
  & $adb -s $dev pull "/sdcard/$ShotTag.png" (Join-Path $dir "$ShotTag.png") | Out-Null
  & $adb -s $dev shell "rm -f /sdcard/$ShotTag.png"
}
# 3. scroll list to bottom
1..6 | ForEach-Object { & $adb -s $dev shell input swipe 839 680 839 300 250; Start-Sleep -Milliseconds 300 }
Start-Sleep -Seconds 1
$n = Dump "end"
$ke = $n | Where-Object { (Txt $_) -eq "Server actions" }
Write-Output "KEBABS=$($ke.Count)"
foreach ($k in $ke) { Write-Output ("  kebab bounds=" + $k.GetAttribute("bounds") + " center=" + (Ctr $k.GetAttribute("bounds"))) }
