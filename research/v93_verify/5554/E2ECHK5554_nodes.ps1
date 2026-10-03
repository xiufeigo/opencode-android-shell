param([string]$Tag = '', [int]$YMin = -1, [int]$YMax = 99999)
$ErrorActionPreference = 'Continue'
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$S = 'emulator-5554'
$d = "C:\sensorsdata\main\program\opencode-android-shell\research\v93_verify\5554"
$xml = "$d\_tmp_nodes.xml"

& $adb -s $S shell uiautomator dump /sdcard/_v93n.xml | Out-Null
& $adb -s $S pull /sdcard/_v93n.xml $xml | Out-Null
$c = [System.IO.File]::ReadAllText($xml, [System.Text.Encoding]::UTF8)

foreach ($m in [regex]::Matches($c, '<node[^>]*>')) {
  $s = $m.Value
  $t = ''
  if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
  $cd = ''
  if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
  $lb = if ($t.Trim().Length -gt 0) { $t } else { $cd }
  if ($lb.Trim().Length -eq 0) { continue }
  if ($Tag -ne '' -and $lb -notmatch $Tag) { continue }
  $bm = [regex]::Match($s, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $bm.Success) { continue }
  $x1 = [int]$bm.Groups[1].Value; $y1 = [int]$bm.Groups[2].Value
  $x2 = [int]$bm.Groups[3].Value; $y2 = [int]$bm.Groups[4].Value
  if ($y1 -lt $YMin -or $y1 -gt $YMax) { continue }
  $cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)
  "{0,-34} [{1},{2}]-[{3},{4}] c=({5},{6})" -f $lb.Substring(0, [Math]::Min(34, $lb.Length)), $x1, $y1, $x2, $y2, $cx, $cy
}
