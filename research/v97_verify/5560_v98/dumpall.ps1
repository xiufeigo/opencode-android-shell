param([string]$Tag = "all")
$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98\evidence"
& $adb -s emulator-5560 shell uiautomator dump /sdcard/$Tag.xml | Out-Null
& $adb -s emulator-5560 pull /sdcard/$Tag.xml "$ev\$Tag.xml" | Out-Null
$x = [xml](Get-Content "$ev\$Tag.xml" -Raw -Encoding UTF8)
$i = 0
foreach ($n in $x.SelectNodes("//node")) {
  $i++
  $t = $n.text; $d = $n."content-desc"
  $lbl = if ($t) { $t } elseif ($d) { "[$d]" } else { "" }
  "{0,3} cls={1,-28} b={2,-24} {3}" -f $i, ($n.class -replace 'android\.widget\.|\.',''), $n.bounds, $lbl
}
