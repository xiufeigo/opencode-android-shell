param([string]$Tag)
$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98\5560_v98_drawer5"

# full dump (all nodes) + pull
& $adb -s emulator-5560 shell uiautomator dump /sdcard/$Tag.xml | Out-Null
& $adb -s emulator-5560 pull /sdcard/$Tag.xml "$ev\$Tag.dump.xml" | Out-Null
# screenshot: screencap -p + pull  (NO exec-out)
& $adb -s emulator-5560 shell screencap -p /sdcard/$Tag.png | Out-Null
& $adb -s emulator-5560 pull /sdcard/$Tag.png "$ev\$Tag.png" | Out-Null

$x = [xml](Get-Content "$ev\$Tag.dump.xml" -Raw -Encoding UTF8)
$nodes = $x.SelectNodes("//node")
$lines = New-Object System.Collections.Generic.List[string]
$sig   = New-Object System.Collections.Generic.List[string]
$i = 0
foreach ($n in $nodes) {
  $i++
  $t = $n.text; $d = $n."content-desc"
  $lbl = if ($t) { $t } else { "" }
  $cd  = if ($d) { "[$d]" } else { "" }
  $cls = ($n.class -replace 'android\.widget\.|\.','')
  $click = $n.clickable
  $lines.Add(("{0,3} cls={1,-26} b={2,-24} clk={3,-5} t='{4}' cd={5}" -f $i,$cls,$n.bounds,$click,$lbl,$cd))
  $sig.Add(("{0}|{1}|{2}|{3}|{4}" -f $cls,$n.bounds,$lbl,$cd,$click))
}
$lines | Out-File "$ev\$Tag.nodes.txt" -Encoding utf8
$sig   | Out-File "$ev\$Tag.sig.txt"  -Encoding utf8

Write-Output "TAG=$Tag  NODECOUNT=$($nodes.Count)"

# bottom bar = clickable nodes whose bottom edge is within 200px of screen bottom
$barY = @()
foreach ($n in $nodes) {
  if ($n.bounds -match '\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]') {
    $y2 = [int]$Matches[4]
    if ($y2 -ge 1400 -and $y2 -le 1600) {
      $d = $n."content-desc"; $t = $n.text
      $barY += ("  barNode b={0}  desc='{1}' text='{2}' clickable={3}" -f $n.bounds,$d,$t,$n.clickable)
    }
  }
}
Write-Output "--- BOTTOM BAND NODES (y2 in 1400..1600): $($barY.Count) ---"
$barY | ForEach-Object { Write-Output $_ }

# labeled SessionTab candidate: clickable AND has content-desc
Write-Output "--- CLICKABLE+CONTENT-DESC NODES ---"
foreach ($n in $nodes) {
  if ($n.clickable -eq "true" -and $n."content-desc") {
    Write-Output ("  {0}  b={1}  desc='{2}'" -f ($n.class -replace 'android\.widget\.|\.',''), $n.bounds, $n."content-desc")
  }
}
