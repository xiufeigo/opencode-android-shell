$adb = "C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dir = "C:\sensorsdata\main\program\opencode-android-shell\research\v97_verify\5556_v98"

function Shot($tag) {
  $png = "$dir\d11_$tag.png"
  & $adb -s emulator-5556 shell screencap -p "/sdcard/d11_$tag.png" | Out-Null
  & $adb -s emulator-5556 pull "/sdcard/d11_$tag.png" $png | Out-Null
}

function Dump($tag) {
  $xml = "$dir\d11_$tag.xml"
  for ($i = 1; $i -le 3; $i++) {
    & $adb -s emulator-5556 shell uiautomator dump /sdcard/d11_dump.xml | Out-Null
    & $adb -s emulator-5556 pull /sdcard/d11_dump.xml $xml | Out-Null
    $len = 0
    if (Test-Path $xml) { $len = (Get-Item $xml).Length }
    if ($len -gt 500) { break }
    Start-Sleep -Seconds 2
  }
  $c = Get-Content $xml -Raw
  "--- [$tag] bytes=$((Get-Item $xml).Length) ---"
  $vals = @()
  $vals += [regex]::Matches($c, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value }
  $vals += [regex]::Matches($c, "text='([^']*)'") | ForEach-Object { $_.Groups[1].Value }
  $vals | Where-Object { $_ -ne "" } | Sort-Object -Unique
}

function Node($tag, $pattern) {
  $xml = "$dir\d11_$tag.xml"
  $c = Get-Content $xml -Raw
  foreach ($m in [regex]::Matches($c, '<node[^>]*>')) {
    $s = $m.Value
    $t = ""
    if ($s -match 'text="([^"]*)"') { $t = $Matches[1] } elseif ($s -match "text='([^']*)'") { $t = $Matches[1] }
    $cd = ""
    if ($s -match 'content-desc="([^"]*)"') { $cd = $Matches[1] }
    $b = $null
    if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') { $b = @($Matches[1], $Matches[2], $Matches[3], $Matches[4]) }
    if (($t -match $pattern) -or ($cd -match $pattern)) {
      $label = if ($t -ne "") { $t } else { $cd }
      if ($b) {
        $cx = [int](([int]$b[0] + [int]$b[2]) / 2)
        $cy = [int](([int]$b[1] + [int]$b[3]) / 2)
        "  '$label' bounds=[$($b[0]),$($b[1])][$($b[2]),$($b[3])] center=$cx,$cy"
      } else { "  '$label' (no bounds)" }
    }
  }
}

function Tap($x, $y) {
  & $adb -s emulator-5556 shell input tap $x $y | Out-Null
}
