param(
  [string]$Serial = 'emulator-5556',
  [string]$Pattern,
  [switch]$Tap,
  [int]$Dx = 0,
  [int]$Dy = 0,
  [string]$Out = 'C:\sensorsdata\main\program\opencode-android-shell\research\v87_verify\5556\ui_tmp.xml'
)
$adb = "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
& $adb -s $Serial shell uiautomator dump /sdcard/ui.xml | Out-Null
& $adb -s $Serial pull /sdcard/ui.xml $Out | Out-Null
$c = Get-Content $Out -Raw
$nodes = [regex]::Matches($c, '<node[^>]*>')
$hits = @()
foreach ($n in $nodes) {
  $s = $n.Value
  $t = ''; if ($s -match 'text="([^"]*)"') { $t = $Matches[1] }
  $d = ''; if ($s -match 'content-desc="([^"]*)"') { $d = $Matches[1] }
  $label = if ($t.Trim() -ne '') { $t } else { $d }
  if ($label -match $Pattern) {
    if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
      $cx = [int](($x1 + $x2)/2) + $Dx
      $cy = [int](($y1 + $y2)/2) + $Dy
      $hits += [pscustomobject]@{ Label=$label; Bounds="$x1,$y1-$x2,$y2"; CX=$cx; CY=$cy; W=($x2-$x1+1); H=($y2-$y1+1) }
    }
  }
}
$hits | Format-Table -AutoSize | Out-String -Width 200
if ($Tap -and $hits.Count -gt 0) {
  $h = $hits[0]
  & $adb -s $Serial shell input tap $h.CX $h.CY
  "TAPPED '$($h.Label)' at $($h.CX),$($h.CY)"
}
