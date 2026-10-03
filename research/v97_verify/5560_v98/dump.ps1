# helper: dump + pull + parse (UTF-8 safe)
param([string]$Tag = "d")
$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98\evidence"
& $adb -s emulator-5560 shell uiautomator dump /sdcard/$Tag.xml | Out-Null
& $adb -s emulator-5560 pull /sdcard/$Tag.xml "$ev\$Tag.xml" | Out-Null
$x = [xml](Get-Content "$ev\$Tag.xml" -Raw -Encoding UTF8)
foreach ($n in $x.SelectNodes("//node")) {
  $t = $n.text; $d = $n."content-desc"
  if ($t -or $d) { "t='$t' desc='$d' b=$($n.bounds) cls=$($n.class)" }
}
