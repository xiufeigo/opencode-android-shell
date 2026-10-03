param([string]$Tag = "d", [switch]$All)
$ErrorActionPreference = 'Stop'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$ev = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5560_v98\5560_v98_drawer2\evidence"
& $adb -s emulator-5560 shell uiautomator dump /sdcard/$Tag.xml | Out-Null
& $adb -s emulator-5560 pull /sdcard/$Tag.xml "$ev\$Tag.xml" | Out-Null
$x = [xml](Get-Content "$ev\$Tag.xml" -Raw -Encoding UTF8)
$nodes = $x.SelectNodes("//node")
"TOTAL_NODES=$($nodes.Count)"
$sem = 0
foreach ($n in $nodes) {
  $t = $n.text; $d = $n."content-desc"
  if ($t -or $d) { $sem++; "SEM[$sem] t='$t' desc='$d' b=$($n.bounds) cls=$($n.class) clickable=$($n.clickable)" }
}
if ($All) { "--- ALL ---"; $i=0; foreach ($n in $nodes) { $i++; "$i $($n.class) b=$($n.bounds) t='$($n.text)' d='$($n.'content-desc')' click=$($n.clickable)" } }
