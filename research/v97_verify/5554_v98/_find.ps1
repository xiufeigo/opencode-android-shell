param([string]$File,[string]$Pattern,[int]$Limit=40)
$ErrorActionPreference='Stop'
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5554_v98"
$raw = Get-Content "$dir\$File" -Raw
$rx = [regex]'(?:text|content-desc)="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
$rx2 = [regex]'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*(?:text|content-desc)="([^"]*)"'
$seen = @{}
foreach ($m in $rx.Matches($raw)) {
  $t = $m.Groups[1].Value
  if ($t -eq "") { continue }
  if ($Pattern -and ($t -notmatch $Pattern)) { continue }
  $b = "[" + $m.Groups[2].Value + "," + $m.Groups[3].Value + "][" + $m.Groups[4].Value + "," + $m.Groups[5].Value + "]"
  $key = "$t|$b"
  if ($seen.ContainsKey($key)) { continue }
  $seen[$key] = 1
  $cx = [math]::Round(([int]$m.Groups[2].Value + [int]$m.Groups[4].Value)/2)
  $cy = [math]::Round(([int]$m.Groups[3].Value + [int]$m.Groups[5].Value)/2)
  Write-Output ("{0,-46} {1,-26} ctr=({2},{3})" -f $t.Substring(0,[math]::Min(46,$t.Length)), $b, $cx, $cy)
}
