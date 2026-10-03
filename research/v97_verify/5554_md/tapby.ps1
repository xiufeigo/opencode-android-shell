# Tap a node found at runtime by exact text OR content-desc. -What "Send"
param([string]$What = "Send")
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d   = "emulator-5554"
$ev  = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_md\evidence"
& $adb -s $d shell uiautomator dump --compressed /sdcard/tb.xml 2>&1 | Out-Null
& $adb -s $d pull /sdcard/tb.xml "$ev\tb.xml" 2>&1 | Out-Null
$raw = Get-Content "$ev\tb.xml" -Raw -Encoding UTF8
$hit = $null
foreach ($m in [regex]::Matches($raw, '<node[^>]*>')) {
  $n = $m.Value
  $tx = [regex]::Match($n,'text="([^"]*)"').Groups[1].Value
  $cd = [regex]::Match($n,'content-desc="([^"]*)"').Groups[1].Value
  if ($tx -eq $What -or $cd -eq $What) { $hit = $n; break }
}
if (-not $hit) { "NODE NOT FOUND: $What"; exit 1 }
$b = [regex]::Match($hit, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
$l=[int]$b.Groups[1].Value; $t=[int]$b.Groups[2].Value
$r=[int]$b.Groups[3].Value; $bt=[int]$b.Groups[4].Value
$cx=[int](($l+$r)/2); $cy=[int](($t+$bt)/2)
"$What center=$cx,$cy bounds=$l,$t-$r,$bt"
& $adb -s $d shell input tap $cx $cy
Start-Sleep -Seconds 2
